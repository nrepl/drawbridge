(ns drawbridge.client
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [nrepl.core :as nrepl]
            [clj-http.client :as http])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

;; Compatibility with the legacy tools.nrepl and the new nREPL 0.4.x.
;; The assumption is that if someone is using old lein repl or boot repl
;; they'll end up using the tools.nrepl, otherwise the modern one.
(if (find-ns 'clojure.tools.nrepl)
  (require
   '[clojure.tools.nrepl :as nrepl]
   '[clojure.tools.nrepl.transport :as transport])
  (require
   '[nrepl.core :as nrepl]
   '[nrepl.transport :as transport]))

(defn ring-client-transport
  "Returns an nREPL client-side transport to connect to HTTP nREPL
   endpoints implemented by `ring-handler`.

   This fn is implicitly registered as the implementation of
   nrepl.core/url-connect for `http` and `https` schemes;
   so, once this namespace is loaded, any tool that uses url-connect
   will use this implementation for connecting to HTTP and HTTPS
   nREPL endpoints."
  [url]
  (let [incoming (LinkedBlockingQueue.)
        fill #(when-let [responses (->> (io/reader %)
                                        line-seq
                                        rest
                                        drop-last
                                        (map json/parse-string)
                                        (remove nil?)
                                        seq)]
                (.addAll incoming responses))
        session-cookies (atom nil)
        http (fn [& [msg]]
               (let [{:keys [cookies body] :as resp} ((if msg http/post http/get)
                                                      url
                                                      (merge {:as :stream
                                                              :cookies @session-cookies}
                                                             (when msg {:form-params msg})))]
                 (swap! session-cookies merge cookies)
                 (fill body)))]
    (transport/FnTransport.
     (fn read [timeout]
       (let [t (System/currentTimeMillis)]
         (or (.poll incoming 0 TimeUnit/MILLISECONDS)
             (when (pos? timeout)
               (http)
               (recur (- timeout (- (System/currentTimeMillis) t)))))))
     http
     (fn close []))))

(.addMethod nrepl/url-connect "http" #'ring-client-transport)
(.addMethod nrepl/url-connect "https" #'ring-client-transport)
