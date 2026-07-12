(ns drawbridge.bridge
  "A local nREPL bridge for Drawbridge HTTP endpoints.

  Most nREPL clients (CIDER, Calva, Conjure, rebel-readline, nREPL's
  own command line) speak bencode over a plain socket and cannot talk
  to a Drawbridge HTTP endpoint directly. This namespace runs a small
  local socket server that accepts those connections and relays every
  message to a remote Drawbridge endpoint over HTTP(S), pushing the
  responses back. Each local connection gets its own HTTP session on
  the remote end, so concurrent clients stay isolated.

  Command-line usage:

      clojure -M -m drawbridge.bridge --url https://my-app.example.com/repl \\
              --port 7888 --token $DRAWBRIDGE_TOKEN

  ...then point any nREPL client at localhost:7888.

  For http(s) URLs, responses are fetched by polling the endpoint
  (see `drawbridge.client` for the throttling details), so output
  arrives with up to ~100ms of latency. For ws(s) URLs the bridge
  speaks the WebSocket transport (`drawbridge.websocket-client`)
  instead, and responses are pushed with no polling delay."
  (:require
   [clojure.walk :as walk]
   [drawbridge.client :as client]
   [drawbridge.websocket-client :as ws-client]
   [nrepl.transport :as transport])
  (:import
   (java.io Closeable)
   (java.net InetAddress ServerSocket Socket)))

(def ^:private default-poll-opts
  "Pacing for the remote->local relay loop. While the connection is
  active we poll eagerly (`:active-poll-ms` per recv); once no message
  has moved in either direction for `:idle-after-ms` we sleep
  `:idle-poll-ms` between polls, so an editor left connected overnight
  doesn't hammer the remote HTTP endpoint. Only spontaneous async
  output is delayed while idle -- any client message snaps the
  connection back to eager polling."
  {:active-poll-ms 100
   :idle-poll-ms 2000
   :idle-after-ms 30000})

(defn- bencode-safe
  "Make a relayed message encodable as bencode, at any nesting depth:
  drop nil-valued map entries (JSON `null`s; bencode renders nil as a
  confusing empty list) and render booleans and non-integer numbers --
  which bencode cannot carry at all -- as strings."
  [msg]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x) (into {} (remove (comp nil? val)) x)
       (boolean? x) (str x)
       (and (number? x) (not (integer? x))) (str x)
       :else x))
   msg))

(defn- remote-transport
  "Open a client transport for `url`, picking the WebSocket transport
  for ws/wss URLs and HTTP long-polling otherwise."
  [url http-headers]
  (if (re-find #"(?i)^wss?://" url)
    (ws-client/websocket-client-transport url {:http-headers http-headers})
    (client/ring-client-transport url {:http-headers http-headers})))

(defn- relay
  "Relay messages between a local socket and the remote Drawbridge
  endpoint at `url` until either side disconnects. Calls `on-close`
  once when the connection winds down."
  [^Socket sock url http-headers poll-opts on-close]
  ;; Open the remote side first: it's the fallible one (unreachable
  ;; endpoint, auth rejection), and at this point no local transport
  ;; resources exist yet for a throw to leak.
  (let [remote (remote-transport url http-headers)
        local (transport/bencode sock)
        {:keys [active-poll-ms idle-poll-ms idle-after-ms]} poll-opts
        open? (atom true)
        last-activity (atom (System/currentTimeMillis))
        close! (fn []
                 (when (compare-and-set! open? true false)
                   (.close sock)
                   ;; The remote side holds real resources (an HTTP
                   ;; session, or a live WebSocket) -- release them.
                   (try
                     (.close ^Closeable remote)
                     (catch Exception _))
                   (on-close)))]
    ;; remote -> local: fetch responses, push them to the client.
    ;; Poll eagerly while active, back off once idle (see
    ;; default-poll-opts). A dead remote surfaces as an exception
    ;; from recv (HTTP: the request throws; WS: recv throws once the
    ;; socket is closed), tearing the relay down.
    (future
      (try
        (while @open?
          (if-let [msg (transport/recv remote active-poll-ms)]
            (do (reset! last-activity (System/currentTimeMillis))
                (transport/send local (bencode-safe msg)))
            (when (> (- (System/currentTimeMillis) @last-activity) idle-after-ms)
              (Thread/sleep ^long idle-poll-ms))))
        (catch Exception _)
        (finally (close!))))
    ;; local -> remote: forward every client message. recv blocks
    ;; until a message arrives and throws once the socket is closed
    ;; (by the client, by stop-bridge, or by close! from the other
    ;; loop), which tears the relay down via close!.
    (future
      (try
        (while @open?
          (when-let [msg (transport/recv local)]
            (reset! last-activity (System/currentTimeMillis))
            (transport/send remote msg)))
        (catch Exception _)
        (finally (close!))))))

(defn start-bridge
  "Start a local nREPL socket server that bridges to the Drawbridge
  endpoint at `:url`.

  Options:

  * `:url` (required) -- the remote Drawbridge endpoint,
    e.g. \"https://my-app.example.com/repl\"
  * `:port` -- local port to listen on (default 0, i.e. a free port)
  * `:bind` -- local address to bind (default \"127.0.0.1\")
  * `:http-headers` -- extra HTTP headers to send with every request,
    e.g. {\"Authorization\" \"Bearer <token>\"}
  * `:active-poll-ms`, `:idle-poll-ms`, `:idle-after-ms` -- pacing of
    the remote polling loop (see `default-poll-opts`)

  Returns a map with `:port` (the bound local port) and `:server`;
  pass it to `stop-bridge` to shut down."
  [{:keys [url port bind http-headers] :or {port 0 bind "127.0.0.1"} :as opts}]
  (when-not url
    (throw (IllegalArgumentException. "A remote Drawbridge :url is required")))
  (let [server (ServerSocket. port 50 (InetAddress/getByName bind))
        poll-opts (merge default-poll-opts
                         (select-keys opts [:active-poll-ms :idle-poll-ms :idle-after-ms]))
        connections (atom #{})]
    (future
      (try
        (loop []
          (let [^Socket sock (.accept server)]
            (swap! connections conj sock)
            ;; A failure to reach the remote endpoint (e.g. a 401 on
            ;; the first request) must not kill the accept loop, and
            ;; the local client should see its connection drop right
            ;; away rather than hang waiting for a handshake.
            (try
              (relay sock url http-headers poll-opts
                     #(swap! connections disj sock))
              (catch Exception e
                (swap! connections disj sock)
                (.close sock)
                (binding [*out* *err*]
                  (println "drawbridge.bridge: could not connect to" url "-"
                           (str e)))))
            (recur)))
        ;; Closing the server socket unblocks accept with an exception.
        (catch Exception _)))
    {:server server
     :connections connections
     :port (.getLocalPort server)}))

(defn stop-bridge
  "Stop a bridge started with `start-bridge`, closing the listening
  socket and any open connections."
  [{:keys [^ServerSocket server connections]}]
  (.close server)
  (doseq [^Socket sock @connections]
    (.close sock)))

(defn- parse-args
  "Parse -main's `--key value` argument pairs into start-bridge
  options. :url is nil when missing."
  [args]
  (let [opts (apply hash-map args)
        token (get opts "--token")]
    (cond-> {:url (get opts "--url")
             :port (or (some-> (get opts "--port") Long/parseLong) 7888)
             :bind (get opts "--bind" "127.0.0.1")}
      token (assoc :http-headers
                   {"Authorization" (str "Bearer " token)}))))

(defn -main
  "Command-line entry point.

  Arguments (as `--key value` pairs):

  * `--url URL` (required) -- the remote Drawbridge endpoint
  * `--port N` -- local port to listen on (default 7888)
  * `--bind ADDR` -- local address to bind (default 127.0.0.1)
  * `--token TOKEN` -- sent as an `Authorization: Bearer` header"
  [& args]
  (let [{:keys [url bind] :as opts} (parse-args args)]
    (when-not url
      (binding [*out* *err*]
        (println "Usage: -m drawbridge.bridge --url URL [--port N] [--bind ADDR] [--token TOKEN]"))
      (System/exit 1))
    (let [bridge (start-bridge opts)]
      (println (format "Drawbridge bridge: nREPL on %s:%d -> %s"
                       bind (:port bridge) url))
      (println "Point your nREPL client (CIDER, Calva, rebel-readline, ...) at this port.")
      @(promise))))
