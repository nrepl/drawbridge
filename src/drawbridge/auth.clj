(ns drawbridge.auth
  "Authentication middleware for Drawbridge endpoints.

  A Drawbridge endpoint is remote code execution by design, so it
  should never be exposed without authentication. `wrap-token`
  provides a minimal, dependency-free bearer-token scheme that pairs
  with the client side's HTTP header support (`.nrepl.edn`'s
  [:drawbridge :http-headers], the bridge's `--token` argument, or
  `ring-client-transport`'s :http-headers option)."
  (:require [cheshire.core :as json])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def ^:private unauthorized-response
  {:status 401
   :headers {"Content-Type" "application/json"
             "WWW-Authenticate" "Bearer"}
   :body (json/generate-string {:error "Unauthorized"
                                :reason "This nREPL endpoint requires a valid bearer token."})})

(defn- constant-time=
  "Compare two strings in constant time, so response timing doesn't
  leak how much of a guessed token matched."
  [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                         (.getBytes b StandardCharsets/UTF_8)))

(defn- bearer-token
  [request]
  (when-let [auth (get-in request [:headers "authorization"])]
    (second (re-matches #"(?i)bearer (.+)" auth))))

(defn wrap-token
  "Middleware that rejects any request not carrying an
  `Authorization: Bearer <token>` header matching `token`, responding
  with a JSON 401. Apply it outside the param middlewares so
  unauthenticated requests are rejected before any parsing."
  [handler token]
  {:pre [(seq token)]}
  (fn [request]
    (if (some-> (bearer-token request) (constant-time= token))
      (handler request)
      unauthorized-response)))
