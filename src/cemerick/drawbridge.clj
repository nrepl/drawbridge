;   Copyright (c) Chas Emerick and other contributors. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
      :author "Chas Emerick"}
     cemerick.drawbridge
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

(def ^{:private true} illegal-method-error
  {:status 405
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:error "Method Not Allowed"
                                :reason "Only GET and POST requests may be submitted to the nREPL endpoint."})})

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

(def response-timeout-header "REPL-Response-Timeout")
(def ^{:private true} response-timeout-header* (.toLowerCase response-timeout-header))

(defn ring-handler
  "Returns a Ring handler implementing an HTTP transport endpoint for nREPL.

   The handler will work when routed onto any URI.  nREPL messages should be
   encoded into POST request parameters; messages are only accepted from POST
   parameters.

   A GET or POST request will respond with any nREPL response messages cached
   since the last request.  If:

     * the handler is created with a non-zero :default-read-timeout, or
     * a session's first request to the handler specifies a non-zero
       timeout via a REPL-Response-Timeout header
 
   ...then each request will wait the specified number of milliseconds for
   additional nREPL responses before finalizing the response.

   All response bodies have an application/json Content-Type, consisting of
   a map in the case of an error, or an array of nREPL response messages
   otherwise.  These messages are output one per line (/ht CouchDB), like so:

   [
   {\"ns\":\"user\",\"value\":\"3\",\"session\":\"d525e5..\"}
   {\"status\":[\"done\"],\"session\":\"d525e5..\"}
   ]

   A custom nREPL handler may be specified when creating the handler via
   :nrepl-handler.  The default
   (via `(clojure.tools.nrepl.server/default-handler)`) is appropriate
   for textual REPL interactions, and includes support for interruptable
   evaluation, sessions, readably-printed evaluation values, and
   prompting for *in* input.  Please refer to the main nREPL documentation
   for details on semantics and message schemas for these middlewares."
  [& {:keys [nrepl-handler default-read-timeout]
      :or {nrepl-handler (server/default-handler)
           default-read-timeout 0}}]
  ;; TODO heartbeat for continuous feeding mode
  (fn [{:keys [params session headers request-method]}]
    (let [msg (clojure.walk/keywordize-keys params)]
      (cond
        (not (#{:post :get} request-method)) illegal-method-error
        
        (and (:op msg) (not= :post request-method)) message-post-error
        
        :else
        (let [[read write :as transport] (or (::transport session)
                                             (transport/piped-transports))
              client (or (::client session)
                         (nrepl/client read (if-let [timeout (get headers (.toLowerCase response-timeout-header*))]
                                              (Long/parseLong timeout)
                                              default-read-timeout)))]
          (response transport client
            (do
              (when (:op msg)
                (future (server/handle* msg nrepl-handler write)))
              (client))))))))

(defn ring-client-transport
  "Returns an nREPL client-side transport to connect to HTTP nREPL
   endpoints implemented by `ring-handler`.

   This fn is implicitly registered as the implementation of
   clojure.tools.nrepl/url-connect for `http` and `https` schemes;
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
                (.addAll incoming responses))]
    (clojure.tools.nrepl.transport.FnTransport.
      (fn read [timeout]
        (let [t (System/currentTimeMillis)]
          (or (.poll incoming 0 TimeUnit/MILLISECONDS)
              (when (pos? timeout)
                (fill (:body (http/get url {:as :stream})))
                (recur (- timeout (- (System/currentTimeMillis) t)))))))
      (fn write [msg]
        (fill (:body (http/post url {:form-params msg
                                     :as :stream}))))
      (fn close []))))

(.addMethod nrepl/url-connect "http" #'ring-client-transport)
(.addMethod nrepl/url-connect "https" #'ring-client-transport)

(comment
  (def app (-> (ring-handler)
             wrap-keyword-params
             wrap-nested-params
             wrap-params
             wrap-session))
  
  (require 'ring.adapter.jetty)
  (defonce server (ring.adapter.jetty/run-jetty #'app {:port 8080 :join? false})))