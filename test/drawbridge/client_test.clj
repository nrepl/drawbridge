(ns drawbridge.client-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures testing]]
            [compojure.core :refer [ANY defroutes]]
            [drawbridge.core :as drawbridge]
            [drawbridge.client]
            [nrepl.core :as nrepl]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.params :refer [wrap-params]]))

(let [nrepl-handler (drawbridge/ring-handler)]
  (defroutes app
    (ANY "/repl" request (nrepl-handler request))))

(def ^:dynamic *port* nil)

(defn server-fixture
  [f]
  (let [server (jetty/run-jetty (-> #'app wrap-keyword-params wrap-nested-params wrap-params)
                               {:port 0 :join? false})
        port (.getLocalPort (first (.getConnectors server)))]
    (try
      (binding [*port* port]
        (f))
      (finally
        (.stop server)))))

(use-fixtures :once server-fixture)

(defn- repl-url []
  (str "http://localhost:" *port* "/repl"))

(defmacro ^:private with-client
  [[client-sym & {:keys [timeout] :or {timeout 1000}}] & body]
  `(with-open [conn# (nrepl/url-connect (repl-url))]
     (let [~client-sym (nrepl/client conn# ~timeout)]
       ~@body)))

(defn- without-unstable-keys
  [res]
  (map #(dissoc % :id :session) res))

(defn- send-message
  [client message]
  (-> (nrepl/message client message)
      without-unstable-keys
      first))

(deftest eval-messages
  (with-client [client]
    (testing "valid form returns value and namespace"
      (is (= {:value "3" :ns "user"}
             (send-message client {:op "eval" :code "(+ 1 2)"}))))

    (testing "invalid form returns reader error"
      (is (re-find #"Syntax error reading source at \(REPL:\d+:\d+\)\.\nEOF while reading"
                   (:err (send-message client {:op "eval" :code "(+ 1 2"})))))

    (testing "exception returns error"
      (is (str/starts-with?
           (:err (send-message client {:op "eval" :code "(throw (ex-info nil {:foo :bar}))"}))
           "Execution error (ExceptionInfo) at user/eval")))

    (testing "stdout is captured in :out key"
      (let [messages (without-unstable-keys
                      (nrepl/message client {:op "eval" :code "(println \"hello\")"}))]
        (is (some #(= "hello\n" (:out %)) messages))))

    (testing "sequential evals in the same session preserve state"
      (send-message client {:op "eval" :code "(def x 42)"})
      (is (= "42" (:value (send-message client {:op "eval" :code "x"})))))))

(deftest nrepl-ops
  (with-client [client]
    (testing "describe returns available ops"
      (is (some? (:ops (send-message client {:op "describe"})))))))

(deftest transport-isolation
  (testing "concurrent clients receive their own responses"
    (with-open [conn-a (nrepl/url-connect (repl-url))
                conn-b (nrepl/url-connect (repl-url))]
      (let [client-a (nrepl/client conn-a 1000)
            client-b (nrepl/client conn-b 1000)]
        (is (= "3" (:value (send-message client-a {:op "eval" :code "(+ 1 2)"}))))
        (is (= "30" (:value (send-message client-b {:op "eval" :code "(+ 10 20)"}))))))))

(deftest polling-is-throttled
  (let [get-count (atom 0)
        nrepl-handler (drawbridge/ring-handler)
        counting-handler (-> (fn [request]
                               (when (= :get (:request-method request))
                                 (swap! get-count inc))
                               (nrepl-handler request))
                             wrap-keyword-params
                             wrap-nested-params
                             wrap-params)
        server (jetty/run-jetty counting-handler {:port 0 :join? false})
        port (.getLocalPort (first (.getConnectors server)))]
    (try
      (with-open [conn (nrepl/url-connect (str "http://localhost:" port "/repl"))]
        (let [client (nrepl/client conn 500)]
          ;; Eval a slow form so the client polls for the full timeout window.
          ;; Server timeout is 0, so each GET returns [] immediately.
          (doall (nrepl/message client {:op "eval" :code "(Thread/sleep 1000)"}))
          ;; With 100ms throttle over ~500ms, expect roughly 5 GETs.
          ;; Without throttle this would be 50+.
          (is (< @get-count 15) "Polling should be throttled to avoid GET floods")))
      (finally
        (.stop server)))))
