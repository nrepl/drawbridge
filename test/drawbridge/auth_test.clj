(ns drawbridge.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [drawbridge.auth :as auth]
            [drawbridge.bridge :as bridge]
            [drawbridge.client :as client]
            [drawbridge.core :as drawbridge]
            [nrepl.core :as nrepl]
            [ring.adapter.jetty :as jetty]))

(defn- ok-handler [_] {:status 200 :body "ok"})

(defn- request-with-auth [value]
  (cond-> {:request-method :get :uri "/repl" :headers {}}
    value (assoc-in [:headers "authorization"] value)))

(deftest wrap-token-test
  (let [handler (auth/wrap-token ok-handler "s3cret")]
    (testing "no Authorization header is rejected"
      (let [resp (handler (request-with-auth nil))]
        (is (= 401 (:status resp)))
        (is (= "Bearer" (get-in resp [:headers "WWW-Authenticate"])))
        (is (= "Unauthorized" (:error (json/parse-string (:body resp) true))))))

    (testing "wrong token is rejected"
      (is (= 401 (:status (handler (request-with-auth "Bearer nope"))))))

    (testing "token in a non-bearer scheme is rejected"
      (is (= 401 (:status (handler (request-with-auth "Basic s3cret"))))))

    (testing "correct token passes through"
      (is (= 200 (:status (handler (request-with-auth "Bearer s3cret"))))))

    (testing "bearer scheme is case-insensitive"
      (is (= 200 (:status (handler (request-with-auth "bearer s3cret")))))))

  (testing "an empty token is not a valid configuration"
    (is (thrown? AssertionError (auth/wrap-token ok-handler "")))))

(deftest secure-ring-handler-config
  (testing "refuses to build an unauthenticated endpoint by default"
    (is (thrown-with-msg? IllegalArgumentException #"Refusing"
                          (drawbridge/secure-ring-handler))))

  (testing ":insecure true is a deliberate opt-out"
    (is (fn? (drawbridge/secure-ring-handler :insecure true))))

  (testing ":token yields a guarded endpoint"
    (let [handler (drawbridge/secure-ring-handler :token "s3cret")]
      (is (= 401 (:status (handler (request-with-auth nil)))))
      (is (= 200 (:status (handler (-> (request-with-auth "Bearer s3cret")))))))))

(defn- with-secure-server
  "Run `f` with the port of a Jetty server exposing a token-guarded
  Drawbridge endpoint at /."
  [token f]
  (let [server (jetty/run-jetty (drawbridge/secure-ring-handler :token token)
                                {:port 0 :join? false})]
    (try
      (f (.getLocalPort (first (.getConnectors server))))
      (finally (.stop server)))))

(deftest secure-end-to-end-url-connect
  (with-secure-server "s3cret"
    (fn [port]
      (let [url (str "http://localhost:" port "/")]
        (testing "client with the right token can eval"
          (with-open [conn (client/ring-client-transport
                            url {:http-headers {"Authorization" "Bearer s3cret"}})]
            (let [client (nrepl/client conn 20000)
                  responses (nrepl/message client {:op "eval" :code "(+ 1 2)"})]
              (is (some #(= "3" (:value %)) responses)))))

        (testing "client without the token is rejected at connect time"
          ;; The transport establishes its session eagerly, so a 401
          ;; surfaces when the transport is created.
          (is (thrown? Exception (client/ring-client-transport url))))))))

(deftest secure-end-to-end-bridge
  (testing "socket client -> bridge --token -> secured endpoint"
    (with-secure-server "s3cret"
      (fn [port]
        (let [b (bridge/start-bridge
                 {:url (str "http://localhost:" port "/")
                  :http-headers {"Authorization" "Bearer s3cret"}})]
          (try
            (with-open [conn (nrepl/connect :port (:port b))]
              ;; Generous window: nrepl/message returns as soon as
              ;; :status done arrives, so this only pays off on slow
              ;; (cold-JIT CI) runs.
              (let [client (nrepl/client conn 20000)
                    responses (nrepl/message client {:op "eval" :code "(* 6 7)"})]
                (is (some #(= "42" (:value %)) responses))))
            (finally (bridge/stop-bridge b))))))))

(deftest bridge-rejected-by-endpoint
  (testing "clients of a misconfigured bridge fail fast instead of hanging"
    (with-secure-server "s3cret"
      (fn [port]
        (let [b (bridge/start-bridge
                 {:url (str "http://localhost:" port "/")
                  :http-headers {"Authorization" "Bearer WRONG"}})]
          (try
            ;; Two rounds: the accept loop must also survive the
            ;; first failed relay and keep serving new connections.
            (dotimes [_ 2]
              (is (thrown? Exception
                           (with-open [conn (nrepl/connect :port (:port b))]
                             (let [client (nrepl/client conn 20000)]
                               (doall (nrepl/message client {:op "eval" :code "(+ 1 2)"})))))))
            (finally (bridge/stop-bridge b))))))))
