;; Copyright (c) Chas Emerick and other contributors. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns drawbridge.core
  "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  {:author "Chas Emerick"}
  (:require [nrepl.core :as nrepl]
            (nrepl [transport :as transport]
                   [server :as server])
            [cheshire.core :as json]
            [clj-http.client :as http]
            [ring.middleware.session.memory :as mem]
            [ring.util.response :as response]
            clojure.walk
            [clojure.java.io :as io])
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
                   (interpose ",\n"))
                 ["\n]"])})

(def response-timeout-header "REPL-Response-Timeout")
(def ^{:private true} response-timeout-header* (.toLowerCase response-timeout-header))

(defn memory-session
  "Wraps the supplied handler in session middleware that uses a
  private memory store. Use the `:cookie-name` option to customize the
  cookie used here. The cookie name defaults to
  \"drawbridge-session\"."
  [handler & {:keys [cookie-name] :or {cookie-name "drawbridge-session"}}]
  (let [store (mem/memory-store)]
    (wrap-session handler {:store store :cookie-name cookie-name})))

(defn ring-handler
  "Returns a Ring handler implementing an HTTP transport endpoint for nREPL.

   The handler will work when routed onto any URI.  Note that this handler
   requires the following standard Ring middleware to function properly:

     * keyword-params
     * nested-params
     * wrap-params

   a.k.a. the Compojure \"api\" stack.

   nREPL messages should be encoded into POST request parameters; messages
   are only accepted from POST parameters.

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
   (via `(nrepl.server/default-handler)`) is appropriate
   for textual REPL interactions, and includes support for interruptable
   evaluation, sessions, readably-printed evaluation values, and
   prompting for *in* input.  Please refer to the main nREPL documentation
   for details on semantics and message schemas for these middlewares."
  [& {:keys [nrepl-handler default-read-timeout cookie-name]
      :or {nrepl-handler (server/default-handler)
           default-read-timeout 0
           cookie-name "drawbridge-session"}}]
  ;; TODO heartbeat for continuous feeding mode
  (-> (fn [{:keys [params session headers request-method] :as request}]
                                        ;(println params session)
        (let [msg (clojure.walk/keywordize-keys params)]
          (cond
           (not (#{:post :get} request-method)) illegal-method-error

           (and (:op msg) (not= :post request-method)) message-post-error

           :else
           (let [[read write :as transport] (or (::transport session)
                                                (transport/piped-transports))
                 client (or (::client session)
                            (nrepl/client read (if-let [timeout (get headers response-timeout-header*)]
                                                 (Long/parseLong timeout)
                                                 default-read-timeout)))]
             (response transport client
                       (do
                         (when (:op msg)
                           (future (server/handle* msg nrepl-handler write)))
                         (client)))))))
      (memory-session :cookie-name cookie-name)))

;; enable easy interactive debugging of typical usage
(def ^{:private true} app (-> (ring-handler)
                            wrap-keyword-params
                            wrap-nested-params
                            wrap-params))

;; as an example:
(defn -main [& args]
  (let [options (clojure.walk/keywordize-keys (apply hash-map args))
        run-jetty (ns-resolve (doto 'ring.adapter.jetty require) 'run-jetty)]
    (run-jetty #'app (merge {:port 0} options))))
