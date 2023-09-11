(ns reitit.ring.server
  (:require
   [com.stuartsierra.component :as component :refer [using]]
   [bsless.companion :as fren :refer [as-component component]]
   [reitit.ring :as ring]
   [reitit.coercion.malli]
   [reitit.openapi :as openapi]
   [reitit.ring.malli]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [reitit.ring.coercion :as coercion]
   [reitit.dev.pretty :as pretty]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.multipart :as multipart]
   [reitit.ring.middleware.parameters :as parameters]
   [meta-merge.core :as mm]
   [ring.adapter.jetty :as jetty]
   [muuntaja.core :as m]
   [clojure.java.io :as io]
   [malli.util :as mu]))

(defn admin-routes
  [_]
  [["/swagger.json"
        {:get {:no-doc true
               :swagger {:info {:title "my-api"
                                :description "swagger docs with [malli](https://github.com/metosin/malli) and reitit-ring"
                                :version "0.0.1"}
                         ;; used in /secure APIs below
                         :securityDefinitions {"auth" {:type :apiKey
                                                       :in :header
                                                       :name "Example-Api-Key"}}
                         :tags [{:name "files", :description "file api"}
                                {:name "math", :description "math api"}]}
               :handler (swagger/create-swagger-handler)}}]
       ["/openapi.json"
        {:get {:no-doc true
               :openapi {:info {:title "my-api"
                                :description "openapi3 docs with [malli](https://github.com/metosin/malli) and reitit-ring"
                                :version "0.0.1"}
                         ;; used in /secure APIs below
                         :components {:securitySchemes {"auth" {:type :apiKey
                                                                :in :header
                                                                :name "Example-Api-Key"}}}}
               :handler (openapi/create-openapi-handler)}}]])

(defn app-routes
  [_]
  [["/files"
    {:tags ["files"]}

    ["/upload"
     {:post {:summary "upload a file"
             :parameters {:multipart [:map [:file reitit.ring.malli/temp-file-part]]}
             :responses {200 {:body [:map [:name string?] [:size int?]]}}
             :handler (fn [{{{:keys [file]} :multipart} :parameters}]
                        {:status 200
                         :body {:name (:filename file)
                                :size (:size file)}})}}]

    ["/download"
     {:get {:summary "downloads a file"
            :swagger {:produces ["image/png"]}
            :responses {200 {:description "an image"
                             :content {"image/png" any?}}}
            :handler (fn [_]
                       {:status 200
                        :headers {"Content-Type" "image/png"}
                        :body (-> "reitit.png"
                                  (io/resource)
                                  (io/input-stream))})}}]]

   ["/math"
    {:tags ["math"]}

    ["/plus"
     {:get {:summary "plus with malli query parameters"
            :parameters {:query [:map
                                 [:x
                                  {:title "X parameter"
                                   :description "Description for X parameter"
                                   :json-schema/default 42}
                                  int?]
                                 [:y int?]]}
            :responses {200 {:body [:map [:total int?]]}}
            :handler (fn [{{{:keys [x y]} :query} :parameters}]
                       {:status 200
                        :body {:total (+ x y)}})}
      :post {:summary "plus with malli body parameters"
             :parameters {:body [:map
                                 [:x
                                  {:title "X parameter"
                                   :description "Description for X parameter"
                                   :json-schema/default 42}
                                  int?]
                                 [:y int?]]}
             ;; OpenAPI3 named examples for request & response
             :openapi {:requestBody
                       {:content
                        {"application/json"
                         {:examples {"add-one-one" {:summary "1+1"
                                                    :value {:x 1 :y 1}}
                                     "add-one-two" {:summary "1+2"
                                                    :value {:x 1 :y 2}}}}}}
                       :responses
                       {200
                        {:content
                         {"application/json"
                          {:examples {"two" {:summary "2"
                                             :value {:total 2}}
                                      "three" {:summary "3"
                                               :value {:total 3}}}}}}}}
             :responses {200 {:body [:map [:total int?]]}}
             :handler (fn [{{{:keys [x y]} :body} :parameters}]
                        {:status 200
                         :body {:total (+ x y)}})}}]]

   ["/secure"
    {:tags ["secure"]
     :openapi {:security [{"auth" []}]}
     :swagger {:security [{"auth" []}]}}
    ["/get"
     {:get {:summary "endpoint authenticated with a header"
            :responses {200 {:body [:map [:secret :string]]}
                        401 {:body [:map [:error :string]]}}
            :handler (fn [request]
                       ;; In a real app authentication would be handled by middleware
                       (if (= "secret" (get-in request [:headers "example-api-key"]))
                         {:status 200
                          :body {:secret "I am a marmot"}}
                         {:status 401
                          :body {:error "unauthorized"}}))}}]]])

(defn routes
  [opts]
  (into (admin-routes opts) (app-routes opts)))

(defn middleware
  [_]
  [;; swagger & openapi
   swagger/swagger-feature
   openapi/openapi-feature
   ;; query-params & form-params
   parameters/parameters-middleware
   ;; content-negotiation
   muuntaja/format-negotiate-middleware
   ;; encoding response body
   muuntaja/format-response-middleware
   ;; exception handling
   exception/exception-middleware
   ;; decoding request body
   muuntaja/format-request-middleware
   ;; coercing response bodys
   coercion/coerce-response-middleware
   ;; coercing request parameters
   coercion/coerce-request-middleware
   ;; multipart
   multipart/multipart-middleware])

(defn coercion
  [opts]
  (-> {;; set of keys to include in error messages
       :error-keys #{#_:type :coercion :in :schema :value :errors :humanized #_:transformed}
       ;; schema identity function (default: close all map schemas)
       :compile mu/closed-schema
       ;; strip-extra-keys (effects only predefined transformers)
       :strip-extra-keys true
       ;; add/set default values
       :default-values true
       ;; malli options
       :options nil}
      (merge opts)
      reitit.coercion.malli/create))

(defn router-data
  [{:keys [coercion middleware muuntaja]}]
  {:coercion coercion
   :muuntaja (or muuntaja m/instance)
   :middleware middleware})

(defn router-opts
  [{:keys [router/data
           router/print-request-diffs?
           router/validate?]}]
  (cond->
      {:exception pretty/exception
       :data data}
    print-request-diffs? (update :reitit.middleware/transform (fnil conj []) (requiring-resolve 'dev/print-request-diffs)) ;; pretty diffs
    validate? (assoc :validate (requiring-resolve 'spec/validate)))) ;; enable spec validation for route data

(defn router
  [{:keys [routes router/opts]}]
  (ring/router routes opts))

(defn swagger-handler
  [{:keys [default-handler]
    :as opts}]
  (ring/routes
   (swagger-ui/create-swagger-ui-handler
    (mm/meta-merge
     {:path "/"
      :config {:validatorUrl nil
               :urls [{:name "swagger", :url "swagger.json"}
                      {:name "openapi", :url "openapi.json"}]
               :urls.primaryName "openapi"
               :operationsSorter "alpha"}}
     opts))
   (or default-handler (ring/create-default-handler))))

(defn ring-handler
  [{:keys [router
           default-handler]}]
  (ring/ring-handler
   router
   (or default-handler (ring/create-default-handler))))

(defn server
  [{:keys [handler] :as opts}]
  (jetty/run-jetty handler (into {} opts)))

(defn make-system
  [_]
  {:coercion (as-component coercion)
   :routes (as-component routes)
   :middleware (as-component middleware)
   :router/data (using (as-component router-data) [:coercion :middleware])
   :router/opts (using (as-component router-opts) [:router/data])
   :router (using (as-component router) [:router/opts :routes])
   :swagger-handler (as-component swagger-handler)
   :handler (using (as-component ring-handler) {:router :router
                                                :default-handler :swagger-handler})
   :server (using (component {:start server
                              :stop (fn [o] (.stop o))
                              :port 8889
                              :max-queued-requests 4096
                              :join? false}) [:handler])})

(comment
  (def sys (component/start-system (fren/into-system (make-system nil))))
  (component/stop-system sys))
