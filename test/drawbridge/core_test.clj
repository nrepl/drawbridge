(ns drawbridge.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [cheshire.core :as json]
            [drawbridge.core :as drawbridge]
            [nrepl.transport :as transport]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]))

(defn- make-handler
  [& opts]
  (-> (apply drawbridge/ring-handler opts)
      wrap-keyword-params
      wrap-nested-params
      wrap-params))

(defn- parse-body
  [body]
  (json/parse-string (apply str body) true))

(defn- encode-params [params]
  (str/join "&" (map (fn [[k v]]
                       (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8")))
                     params)))

(defn- request
  [method & [params headers]]
  (let [qs (when (seq params) (encode-params params))]
    (cond-> {:request-method method
             :uri "/repl"
             :headers (or headers {})}
      (and (= method :get) qs)
      (assoc :query-string qs)

      (= method :post)
      (-> (assoc :body (java.io.ByteArrayInputStream.
                        (.getBytes (or qs "") "UTF-8")))
          (assoc-in [:headers "content-type"] "application/x-www-form-urlencoded")))))

(defn- get-set-cookie [response]
  (let [v (get-in response [:headers "Set-Cookie"])]
    (cond
      (string? v) v
      (sequential? v) (str/join "; " v))))

(defn- extract-session-cookie [response cookie-name]
  (some->> (get-set-cookie response)
           (re-find (re-pattern (str cookie-name "=([^;]+)")))
           second))

(deftest method-rejection
  (let [handler (make-handler)]
    (testing "unsupported methods return 405 with illegal method error"
      (are [method] (let [resp (handler (request method))
                          body (json/parse-string (:body resp) true)]
                      (and (= 405 (:status resp))
                           (= "Method Not Allowed" (:error body))
                           (re-find #"Only GET and POST" (:reason body))))
        :put :delete :patch))

    (testing "GET with an op parameter returns 405"
      (let [resp (handler (request :get {:op "eval" :code "(+ 1 2)"}))
            body (json/parse-string (:body resp) true)]
        (is (= 405 (:status resp)))
        (is (= "Method Not Allowed" (:error body)))
        (is (re-find #"POST request method" (:reason body)))))))

(deftest ring-handler-responses
  (testing "GET returns empty response array"
    (let [handler (make-handler)
          resp (handler (request :get))]
      (is (= 200 (:status resp)))
      (is (= [] (parse-body (:body resp))))))

  (testing "POST eval returns result with correct content type and done status"
    (let [handler (make-handler :default-read-timeout 5000)
          resp (handler (request :post {:op "eval" :code "(+ 1 2)"}))
          messages (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "Content-Type"])))
      (is (some #(= "3" (:value %)) messages))
      (is (some #(contains? (set (:status %)) "done") messages))))

  (testing "REPL-Response-Timeout header controls read timeout"
    (let [handler (make-handler)
          resp (handler (request :post
                                 {:op "eval" :code "(+ 2 3)"}
                                 {"repl-response-timeout" "5000"}))
          messages (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (some #(= "5" (:value %)) messages))))

  (testing "default-read-timeout 0 returns empty when eval is pending"
    (let [handler (make-handler)
          resp (handler (request :post {:op "eval" :code "(+ 1 2)"}))
          messages (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= [] messages))))

  (testing "GET retrieves deferred eval results via polling"
    ;; The sleep guarantees the eval is still pending when the POST
    ;; (read timeout 0) returns; on a warm JVM a bare (+ 5 6) can
    ;; finish before the POST drains its responses.
    (let [handler (make-handler)
          resp1 (handler (request :post {:op "eval"
                                         :code "(do (Thread/sleep 300) (+ 5 6))"}))
          cookie (extract-session-cookie resp1 "drawbridge-session")]
      (is (= [] (parse-body (:body resp1))))
      (let [deadline (+ (System/currentTimeMillis) 10000)
            poll (fn []
                   (parse-body
                    (:body (handler (-> (request :get)
                                        (assoc-in [:headers "cookie"]
                                                  (str "drawbridge-session=" cookie)))))))
            messages (loop []
                       (let [msgs (poll)]
                         (if (or (some #(= "11" (:value %)) msgs)
                                 (> (System/currentTimeMillis) deadline))
                           msgs
                           (do (Thread/sleep 100) (recur)))))]
        (is (some #(= "11" (:value %)) messages))))))

(deftest session-handling
  (testing "session cookie is set and can be reused"
    (let [handler (make-handler :default-read-timeout 5000)
          cookie-name "drawbridge-session"
          resp1 (handler (request :post {:op "eval" :code "(+ 10 20)"}))
          cookie (extract-session-cookie resp1 cookie-name)]
      (is (some? cookie) "Session cookie should be set")
      (let [resp2 (handler (-> (request :get)
                               (assoc-in [:headers "cookie"]
                                         (str cookie-name "=" cookie))))]
        (is (= 200 (:status resp2))))))

  (testing "custom cookie name is used in Set-Cookie header"
    (let [handler (make-handler :cookie-name "my-session")
          resp (handler (request :get))
          set-cookie (get-set-cookie resp)]
      (is (some? set-cookie))
      (is (re-find #"my-session=" set-cookie)))))

;; Regression test for #11: early Drawbridge stored its transport in
;; the application's shared ring session, clobbering the app's own
;; session data. The handler now keeps its state in a private memory
;; store under its own cookie, so an app-level session must survive
;; REPL traffic untouched.
(deftest app-ring-session-is-not-clobbered
  (let [app-sessions (atom {})
        drawbridge-handler (make-handler :default-read-timeout 5000)
        handler (-> (fn [req]
                      (if (= "/repl" (:uri req))
                        (drawbridge-handler req)
                        {:status 200
                         :session (assoc (:session req) :user "bozhidar")
                         :body (str (get-in req [:session :user]))}))
                    (wrap-session {:store (mem/memory-store app-sessions)}))]
    ;; establish an app session
    (let [resp1 (handler {:request-method :get :uri "/app" :headers {}})
          app-cookie (extract-session-cookie resp1 "ring-session")]
      (is (some? app-cookie) "app session cookie should be set")

      ;; hit the nREPL endpoint with the app session cookie attached
      (let [resp2 (handler (-> (request :post {:op "eval" :code "(+ 1 2)"})
                               (assoc-in [:headers "cookie"]
                                         (str "ring-session=" app-cookie))))]
        (is (= 200 (:status resp2)))
        (testing "drawbridge answers with its own cookie, not the app's"
          (let [set-cookie (or (get-set-cookie resp2) "")]
            (is (re-find #"drawbridge-session=" set-cookie))
            (is (not (re-find #"ring-session=" set-cookie))))))

      (testing "the app session survives REPL traffic"
        (let [resp3 (handler (-> {:request-method :get :uri "/app" :headers {}}
                                 (assoc-in [:headers "cookie"]
                                           (str "ring-session=" app-cookie))))]
          (is (= "bozhidar" (:body resp3)))))

      (testing "no drawbridge internals leak into the app session store"
        (is (not-any? (fn [[_ session]]
                        (some #(= "drawbridge.core" (namespace %))
                              (filter keyword? (keys session))))
                      @app-sessions))))))

(deftest custom-nrepl-handler
  (testing "responses come from the supplied handler"
    (let [marker "custom-handler-marker"
          custom-handler (fn [msg]
                           (transport/send (:transport msg)
                                           {:status :done :value marker}))
          handler (make-handler :nrepl-handler custom-handler
                                :default-read-timeout 5000)
          resp (handler (request :post {:op "eval" :code "ignored"}))
          messages (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (some #(= marker (:value %)) messages)))))
