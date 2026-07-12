(ns drawbridge.websocket
  "WebSocket transport support for nREPL, server side.

  Unlike the HTTP long-poll transport in `drawbridge.core`, a
  WebSocket connection lets the server push nREPL responses as they
  are produced -- no polling, no per-request session cookies, and a
  connection maps naturally onto a stateful nREPL client. Prefer this
  transport when your deployment environment allows WebSocket
  upgrades; keep the HTTP transport as a fallback for environments
  that don't.

  Messages are JSON-encoded text frames in both directions, one nREPL
  message per frame.

  See `drawbridge.websocket-client` for the matching client-side
  transport."
  (:require
   [cheshire.core :as json]
   [nrepl.server :as server]
   [nrepl.transport :as transport]
   [ring.websocket :as ws]))

(def ^:private upgrade-required-response
  {:status 426
   :headers {"Content-Type" "application/json"
             "Upgrade" "websocket"}
   :body (json/generate-string
          {:error "Upgrade Required"
           :reason "This nREPL endpoint speaks WebSocket; send an upgrade request."})})

(defn- websocket-transport
  "An nREPL transport that pushes response messages to the client as
  JSON text frames. The server never reads from a transport (messages
  arrive via `on-message`), so `recv` always returns nil."
  [socket]
  (let [lock (Object.)]
    (reify transport/Transport
      (send [this msg]
        ;; ws/send is not guaranteed to be safe under concurrent
        ;; writers (e.g. an eval's stdout racing its :done message).
        (locking lock
          (ws/send socket (json/generate-string msg)))
        this)
      (recv [_this] nil)
      (recv [_this _timeout] nil))))

(defn ring-handler
  "Returns a Ring handler implementing a WebSocket transport endpoint
   for nREPL. Requires a Ring adapter with WebSocket support (e.g.
   ring-jetty-adapter 1.11+).

   Each WebSocket connection gets its own transport; incoming text
   frames are JSON-decoded and dispatched to the nREPL handler, and
   every response is pushed back as a JSON text frame as soon as it
   is produced.

   Options:

     * :nrepl-handler -- a custom nREPL handler
       (default: `(nrepl.server/default-handler)`)
     * :fallback -- a Ring handler for plain HTTP requests, letting
       one route serve both this transport and the HTTP long-poll
       transport of `drawbridge.core/ring-handler`
       (default: respond with 426 Upgrade Required)

   No param middleware is needed, in contrast to
   `drawbridge.core/ring-handler`."
  [& {:keys [nrepl-handler fallback]
      :or {nrepl-handler (server/default-handler)
           fallback (constantly upgrade-required-response)}}]
  (fn [request]
    (if (ws/upgrade-request? request)
      {::ws/listener
       {:on-open (fn [_socket])
        :on-message (fn [socket message]
                      (let [msg (json/parse-string (str message) true)
                            transport (websocket-transport socket)]
                        (future (server/handle* msg nrepl-handler transport))))
        :on-error (fn [socket _throwable]
                    (when (ws/open? socket)
                      (ws/close socket 1011 "nREPL transport error")))
        :on-close (fn [_socket _code _reason])}}
      (fallback request))))
