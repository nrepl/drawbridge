(ns drawbridge.client
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clj-http.client :as http]
   [nrepl.config]
   [nrepl.core :as nrepl]
   [nrepl.transport :as transport])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def default-http-headers
  "Extra HTTP headers configured under [:drawbridge :http-headers] in
   the nREPL config (e.g. `.nrepl.edn`). All Drawbridge client
   transports send these when no :http-headers option is given."
  (get-in nrepl.config/config [:drawbridge :http-headers]))

(defn ring-client-transport
  "Returns an nREPL client-side transport to connect to HTTP nREPL
   endpoints implemented by `ring-handler`.

   Accepts an options map with the following keys:

   * `:http-headers` -- extra HTTP headers to send with every request,
     e.g. {\"Authorization\" \"Bearer <token>\"}. When nil or absent,
     falls back to `default-http-headers` (the nREPL config); pass an
     empty map to send no extra headers despite the config.

   This fn is implicitly registered as the implementation of
   `nrepl.core/url-connect` for `http` and `https` schemes;
   so, once this namespace is loaded, any tool that uses `url-connect`
   will use this implementation for connecting to HTTP and HTTPS
   nREPL endpoints."
  ([url] (ring-client-transport url nil))
  ([url {:keys [http-headers]}]
   ;; `or`, not destructuring :or -- callers passing an explicit nil
   ;; (e.g. the bridge without --token) must still get the config
   ;; default, and :or only fires when the key is absent.
   (let [http-headers (or http-headers default-http-headers)
         incoming (LinkedBlockingQueue.)
         fill (fn [body]
                (when-let [responses (->> (io/reader body)
                                          line-seq
                                          rest
                                          drop-last
                                          (map #(json/parse-string % true))
                                          (remove nil?)
                                          seq)]
                  (.addAll incoming responses)))
         session-cookies (atom nil)
         http (fn [& [msg]]
                (let [{:keys [cookies body] :as resp} ((if msg http/post http/get)
                                                       url
                                                       (merge {:as :stream
                                                               :cookies @session-cookies}
                                                              (when msg {:form-params msg})
                                                              (when http-headers {:headers http-headers})))]
                  (swap! session-cookies merge cookies)
                  (fill body)))]
     (transport/->FnTransport
      ;; Read the next response message, polling the server via HTTP.
      ;; First checks the local queue without blocking. If empty and time
      ;; remains, fires an HTTP GET (which may fill the queue via `fill`),
      ;; then waits up to 100ms for a result before retrying. The 100ms
      ;; cap avoids flooding the server with GETs when no responses are
      ;; pending (see #10).
      (fn read [timeout]
        (let [t (System/currentTimeMillis)]
          (or (.poll incoming 0 TimeUnit/MILLISECONDS)
              (when (pos? timeout)
                (http)
                (or (.poll incoming (min timeout 100) TimeUnit/MILLISECONDS)
                    (let [remaining (- timeout (- (System/currentTimeMillis) t))]
                      (when (pos? remaining)
                        (recur remaining))))))))
      http
      (fn close [])))))

(.addMethod nrepl/url-connect "http" #'ring-client-transport)
(.addMethod nrepl/url-connect "https" #'ring-client-transport)
