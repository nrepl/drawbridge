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
   [drawbridge.client :as client]
   [drawbridge.websocket-client :as ws-client]
   [nrepl.transport :as transport])
  (:import
   (java.net InetAddress ServerSocket Socket)))

(def ^:private poll-timeout
  "Milliseconds to wait per `recv` poll in the relay loops. For the
  remote (HTTP) side this bounds how often an idle connection polls
  the endpoint; for the local side it merely bounds how often the
  loop rechecks that the connection is still open."
  100)

(defn- strip-nils
  "Remove nil-valued entries from a message. JSON `null`s in HTTP
  responses decode to nil, but bencode cannot encode nil."
  [msg]
  (into {} (remove (comp nil? val)) msg))

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
  [^Socket sock url http-headers on-close]
  (let [local (transport/bencode sock)
        remote (remote-transport url http-headers)
        open? (atom true)
        close! (fn []
                 (when (compare-and-set! open? true false)
                   (.close sock)
                   (on-close)))]
    ;; remote -> local: poll the HTTP endpoint, push responses back.
    (future
      (try
        (while @open?
          (when-let [msg (transport/recv remote poll-timeout)]
            (transport/send local (strip-nils msg))))
        (catch Exception _)
        (finally (close!))))
    ;; local -> remote: forward every client message as an HTTP POST.
    ;; A disconnected client surfaces as a SocketException from recv.
    (future
      (try
        (while @open?
          (when-let [msg (transport/recv local poll-timeout)]
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

  Returns a map with `:port` (the bound local port) and `:server`;
  pass it to `stop-bridge` to shut down."
  [{:keys [url port bind http-headers] :or {port 0 bind "127.0.0.1"}}]
  (when-not url
    (throw (IllegalArgumentException. "A remote Drawbridge :url is required")))
  (let [server (ServerSocket. port 50 (InetAddress/getByName bind))
        connections (atom #{})]
    (future
      (try
        (loop []
          (let [^Socket sock (.accept server)]
            (swap! connections conj sock)
            (relay sock url http-headers
                   #(swap! connections disj sock))
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

(defn -main
  "Command-line entry point.

  Arguments (as `--key value` pairs):

  * `--url URL` (required) -- the remote Drawbridge endpoint
  * `--port N` -- local port to listen on (default 7888)
  * `--bind ADDR` -- local address to bind (default 127.0.0.1)
  * `--token TOKEN` -- sent as an `Authorization: Bearer` header"
  [& args]
  (let [opts (apply hash-map args)
        url (get opts "--url")
        port (or (some-> (get opts "--port") Long/parseLong) 7888)
        bind (get opts "--bind" "127.0.0.1")
        token (get opts "--token")]
    (when-not url
      (binding [*out* *err*]
        (println "Usage: -m drawbridge.bridge --url URL [--port N] [--bind ADDR] [--token TOKEN]"))
      (System/exit 1))
    (let [bridge (start-bridge
                  (cond-> {:url url :port port :bind bind}
                    token (assoc :http-headers
                                 {"Authorization" (str "Bearer " token)})))]
      (println (format "Drawbridge bridge: nREPL on %s:%d -> %s"
                       bind (:port bridge) url))
      (println "Point your nREPL client (CIDER, Calva, rebel-readline, ...) at this port.")
      @(promise))))
