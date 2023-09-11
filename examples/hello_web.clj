(ns hello-web
  "Adapted from https://mccue.dev/pages/12-7-22-clojure-web-primer"
  (:require
   [com.stuartsierra.component :as component :refer [using]]
   [bsless.companion :as fren :refer [as-component component]]
   [ring.adapter.jetty :as jetty]
   [next.jdbc :as jdbc]
   [hikari-cp.core :as hikari]))

(defn make-hello-handler
  [{:keys [datasource sendgrid-client]}]
  (fn [request]
    (sendgrid-client "admin@website.com" "You got a user!")
    (let [user-id   (:user-id request)
          user-name (datasource ["SELECT name FROM users WHERE users.user_id = ?" user-id])]
      {:status 200
       :body   (str "Hello, " user-name)})))

#_
(defn make-sendgrid-client
  [api-key]
  {:api-key api-key
   :client  (hato/build-http-client {:connect-timeout 10000
                                     :redirect-policy :always})})

#_
(defn make-wikipedia-client
  []
  ;; Nothing really to put...
  {:name "Wikipedia Client"})

#_
(defn get-marco-polo-info
  [{:keys [client]}]
  (slurp "https://en.wikipedia.org/wiki/Marco_Polo"))

#_
(defn marco-handler
  [wikipedia-client request]
  (if (= (:query-string request) "nopool")
    {:status 200
     :body   (get-marco-polo-info wikipedia-client)}
    {:status 200
     :body   "POLO!"}))

(defn handler
  [{:keys [hello-handler marco-handler]}]
  (fn [request]
    (cond
      (= (:uri request) "/hello")
      (hello-handler request)

      (= (:uri request) "/marco")
      (marco-handler request)

      :else
      {:status 404})))

(defn server
  [{:keys [handler] :as opts}]
  (jetty/run-jetty handler (into {} opts)))

(defn make-system
  []
  {:datasource (component {:start hikari/make-datasource
                           :step (fn [ds & args] (apply jdbc/execute-one! ds args))
                           :stop hikari/close-datasource})
   :hello-handler (-> {:start make-hello-handler}
                      as-component
                      (using [:datasource :sendgrid-client]))
   :marco-handler (fn [_] {:status 200 :body "POLO!"})
   :handler (-> {}
                as-component
                (using [:hello-handler :macro-handler]))
   :server (-> {:start server
                :stop (fn [o] (.stop o))}
               component
               (using [:handler]))})

(defn configure
  [system]
  (merge-with
   merge
   system
   {:datasource {:adapter "h2"
                 :url     "jdbc:h2:~/test"}
    :server {:port 8889
             :max-queued-requests 4096
             :join? false}}))

(comment
  (def ds (hikari/make-datasource {:adapter "h2"
                                   :url     "jdbc:h2:~/test"}))

  (jdbc/execute! ds ["CREATE TABLE users (user_id INT NOT NULL);"])
  (jdbc/execute! ds ["insert into users values (1);"])
  (jdbc/execute! ds ["insert into users values (3);"])
  (jdbc/execute! ds ["insert into users values (4);"]))
