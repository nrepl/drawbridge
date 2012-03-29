(ns cemerick.drawbridge
  (:require [clojure.tools.nrepl :as nrepl]
            (clojure.tools.nrepl [transport :as transport]
                                 [server :as server])
            [cheshire.core :as json]
            [ring.util.response :as response]
            clojure.walk
            [clojure.java.io :as io]
            [clj-http.client :as http])
  (:use (ring.middleware params keyword-params nested-params session))
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^{:private true} message-post-error
  {:status 405
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:error "Method Not Allowed"
                                :reason "Sending an nREPL message requires a POST request method."})})

(defn- response
  [transport client response-seq]
  {:status 200
   :session {::transport transport
             ::client client}
   :headers {"Content-Type" "application/json"}
   :body (concat ["[\n"]
                 (->> (map json/generate-string response-seq)
                   (interpose "\n"))
                 ["\n]"])})

(defn ring-handler
  [& {:keys [nrepl-handler default-read-timeout]
      :or {nrepl-handler (server/default-handler)
           default-read-timeout 1000}}]
  ;; TODO heartbeat for continuous feeding mode
  (fn [{:keys [params session headers request-method]}]
    (let [msg (clojure.walk/keywordize-keys params)]
      (if (and (:op msg) (not= :post request-method))
        message-post-error        
        (let [[read write :as transport] (or (::transport session)
                                             (transport/piped-transports))
              client (or (::client session)
                         (nrepl/client read (get headers "REPL-Timeout" default-read-timeout)))]
          (response transport client
            (do
              (when (:op msg)
                (future (server/handle* msg nrepl-handler write)))
              (client))))))))

#_(def app (-> (ring-handler)
           wrap-keyword-params
           wrap-nested-params
           wrap-params
           wrap-session))

#_(require 'ring.adapter.jetty)
#_(defonce server (ring.adapter.jetty/run-jetty #'app {:port 8080 :join? false}))

(defn ring-client-transport
  [url]
  (let [incoming (LinkedBlockingQueue.)
        fill #(when-let [responses (->> (io/reader %)
                                     line-seq
                                     rest
                                     drop-last
                                     (map json/parse-string)
                                     (remove nil?)
                                     seq)]
                (.addAll incoming responses))]
    (clojure.tools.nrepl.transport.FnTransport.
      (fn read [timeout]
        (let [t (System/currentTimeMillis)]
          (or (.poll incoming 0 TimeUnit/MILLISECONDS)
              (when (pos? timeout)
                (fill (:body (http/get url {:as :stream})))
                (recur (- timeout (- (System/currentTimeMillis) t)))))))
      (fn write [msg]
        (fill (:body (http/post url {:form-params msg :as :stream}))))
      (fn close []))))
