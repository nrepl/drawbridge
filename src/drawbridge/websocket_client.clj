(ns drawbridge.websocket-client
  "WebSocket transport support for nREPL, client side.

  Connects to endpoints implemented by
  `drawbridge.websocket/ring-handler`. Responses are pushed by the
  server as they are produced, so unlike the HTTP long-poll transport
  in `drawbridge.client` there is no polling and no added latency.

  Uses the JDK's built-in WebSocket client (java.net.http), so no
  extra dependencies are required."
  (:require
   [cheshire.core :as json]
   [nrepl.core :as nrepl]
   [nrepl.transport :as transport])
  (:import
   (java.net URI)
   (java.net.http HttpClient WebSocket WebSocket$Listener)
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- queueing-listener
  "A WebSocket listener that reassembles text frames into complete
  messages, JSON-decodes them, and puts them on `incoming`."
  [^LinkedBlockingQueue incoming closed?]
  (let [buf (StringBuilder.)]
    (reify WebSocket$Listener
      (onOpen [_this ws]
        (.request ws 1))
      (onText [_this ws data last?]
        ;; A logical message may span multiple frames; accumulate
        ;; until the final one.
        (.append buf data)
        (when last?
          (let [s (.toString buf)]
            (.setLength buf 0)
            (.put incoming (json/parse-string s true))))
        (.request ws 1)
        nil)
      (onError [_this _ws _err]
        (reset! closed? true))
      (onClose [_this _ws _code _reason]
        (reset! closed? true)
        nil))))

(defn websocket-client-transport
  "Returns an nREPL client-side transport speaking JSON over
   WebSocket, for endpoints implemented by
   `drawbridge.websocket/ring-handler`.

   Accepts an options map with the following keys:

   * `:http-headers` -- extra headers to send with the upgrade request,
     e.g. {\"Authorization\" \"Bearer <token>\"}

   This fn is implicitly registered as the implementation of
   `nrepl.core/url-connect` for the `ws` and `wss` schemes."
  ([url] (websocket-client-transport url nil))
  ([url {:keys [http-headers]}]
   (let [incoming (LinkedBlockingQueue.)
         closed? (atom false)
         builder (reduce-kv (fn [b k v] (.header b (name k) (str v)))
                            (.newWebSocketBuilder (HttpClient/newHttpClient))
                            (or http-headers {}))
         ^WebSocket ws (-> builder
                           (.buildAsync (URI/create url)
                                        (queueing-listener incoming closed?))
                           (.join))
         send-lock (Object.)]
     (transport/->FnTransport
      (fn read [timeout]
        (.poll incoming timeout TimeUnit/MILLISECONDS))
      (fn write [msg]
        ;; The JDK client allows only one outstanding text send.
        (locking send-lock
          (.join (.sendText ws (json/generate-string msg) true))))
      (fn close []
        (when (compare-and-set! closed? false true)
          (try
            (.join (.sendClose ws WebSocket/NORMAL_CLOSURE "bye"))
            (catch Exception _))))))))

(.addMethod nrepl/url-connect "ws" #'websocket-client-transport)
(.addMethod nrepl/url-connect "wss" #'websocket-client-transport)
