(ns hedge.azure.function-app
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [camel-snake-kebab.core :refer [->camelCaseString ->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [goog.object :as gobj]
            [cljs.core.async :refer [<!]]
            [cljs.core.async.impl.protocols :refer [ReadPort]]
            [clojure.string :as str]
            [clojure.walk :as w]
            [taoensso.timbre :as timbre
                             :refer [log  trace  debug  info  warn  error  fatal  report
                                     logf tracef debugf infof warnf errorf fatalf reportf
                                     spy get-env log-env]]
            [oops.core :as oops]
            [hedge.azure.timbre-appender :refer [timbre-appender]]
            [hedge.common :refer [outputs->atoms]]))


(defprotocol Codec
  (serialize [this data])
  (deserialize [this message]))

(extend-protocol Codec
  nil
    (serialize [this data] (->> data
                                (transform-keys ->camelCaseString)
                                clj->js))
    (deserialize [this message] (->> message
                                     js->clj
                                     (transform-keys ->kebab-case-keyword))))

(defn serialize-response 
  [codec resp]
  (let [g (serialize codec resp)]
    (cond-> g
        (and (map? resp) (:headers resp)) (gobj/set "headers" (clj->js (:headers resp))))
    g))

(defn dig [headers header-name clean-fn]
  (let [header (get headers header-name)]
    (if (some? header)
      (clean-fn header))))

(defn bindings->inputs
  "Context.bindings mapping for inputs"
  [context inputs]
  (into {} 
    (map 
      (fn [input] {(-> input :key keyword) (oops/oget+ context (str "bindings." (-> input :key)))})
      inputs)))

(defn outputs->bindings
   "bind outputs to bindings"
   [context outputs]
   (info "creating bindings" outputs)
   (doseq [output outputs]
    
   ;(map (fn [output] 
   (do
    (info "---output element--" output)
    (info "keyname " (-> output val :key))
    (info "value" @(-> output val :value)) 
    (info "context-state before write: " (js->clj (oops/oget context "bindings")))
    
    (cond
      ; write to queue
      (= :queue (-> output val :type))
        (oops/oset!+ 
          context 
          (str "bindings." (-> output val :key)) 
          (clj->js @(-> output val :value)))
      
      ; write to cosmodb
      (= :db (-> output val :type))
        (doseq [item @(-> output val :value)]
          (oops/oset!+
            context
            (str "bindings." (-> output val :key))
            (js/JSON.stringify (clj->js item))))

      ; write to table storage
      (= :table (-> output val :type))
        (doseq [item @(-> output val :value)]
          (oops/ocall+ 
            (oops/oget context (str "bindings." (-> output val :key name)))
            "push"
            (clj->js item))))


    (info "context-state after write: " (js->clj (oops/oget context "bindings"))))))

(defn azure->ring 
  [req]
  (let [r       (js->clj req)
        headers (get r "headers")]
    {:server-port    -1
     :server-name     (get headers "Host")
     :remote-addr     (dig headers "x-forwarded-for" #(-> % (str/split #"," 2) first))
     :uri             (get headers "x-original-url")
     :query-string    (-> (get r "originalUrl") (str/split #"\?" 2) second)
     :scheme          (-> (get r "originalUrl") (str/split #":" 2) first keyword)
     :request-method  (-> (get r "method") str/lower-case keyword)
     :protocol        "HTTP/1.1"      ; TODO: figure out if this can ever be anything else
     :ssl-client-cert nil             ; TODO: we have the client cert string but not as Java type...
     :headers         headers
     :body            (get r "body")}))  ; TODO: should use codec or smth probably to handle request body type
  
(defn ring->azure [context & {:keys [outputs]}]
(fn [raw-resp]
  (trace (str "result: " raw-resp))
  (info "before binding outputs: " (js->clj (oops/oget context "bindings")))
  (outputs->bindings context outputs)
  (info "after binding outputs: " (js->clj (oops/oget context "bindings")))
  (if (string? raw-resp)
    (.done context nil (clj->js {:body raw-resp}))
    (.done context nil (clj->js raw-resp)))))

(defn azure->timer
  "Converts incoming timer trigger to Hedge timer handler"
  [timer]
  (let [timer (js->clj timer)]
    {:trigger-time (str (get timer "next") \Z)}))   ; Azure times are UTC but timestamps miss TimeZone

(defn timer->azure
  "Returns timers result to azure"
  [context codec]
  (fn [raw-resp]
    (trace (str "result: " raw-resp))
    (.done context nil (clj->js raw-resp))))

(defn azure->queue
  "Converts incoming queue message to Hedge queue message handler"
  [message]
  (let [message (js->clj message)]
    {:payload message}))

(defn queue->azure
  "Returns queue triggered handlers result to azure"
  [context codec]
  (fn [raw-resp]
    (trace (str "result: " raw-resp))
    (.done context nil (clj->js raw-resp))))

(defn azure-api-function-wrapper
  "wrapper used for http in / http out api function"
  [handler & {:keys [inputs outputs]}]
  ;  (azure-api-function-wrapper handler nil))
  ; ([handler codec]
    (fn [context req]
      (try
        (timbre/merge-config! {:appenders {:console nil}})
        (timbre/merge-config! {:appenders {:azure (timbre-appender (.-log context))}})
        (trace (str "request: " (js->clj req)))
        (def opatoms (outputs->atoms outputs))
        (info "def opatoms" opatoms)
        (let [
              ok      (ring->azure context :outputs opatoms)
              logfn   (.-log context)
              result  (handler (into (azure->ring req) {:log logfn}) 
                              :inputs (bindings->inputs context inputs) 
                              :outputs opatoms)]

          (info "i/o: " inputs outputs)
          (info "context.bindings:" (js->clj (oops/oget context "bindings")))
          (cond
            (satisfies? ReadPort result) (do (info "Result is channel, content pending...")
                                            (go (ok (<! result))))
            (string? result)             (ok {:body result})
            :else                        (ok result)))
        (catch :default e (.done context e nil)))))

(defn azure-timer-function-wrapper
  "wrapper used for timer-triggered function"
  ([handler]
    (azure-timer-function-wrapper handler nil))
  ([handler codec]
    (fn [context timer]
      (try 
        (timbre/merge-config! {:appenders {:console nil}})
        (timbre/merge-config! {:appenders {:azure (timbre-appender (.-log context))}})
        (trace (str "timer: " (js->clj timer)))
        (let [ok     (timer->azure context codec)
              logfn  (.-log context)
              result (handler (into (azure->timer timer) {:log logfn}))]

          (cond
            (satisfies? ReadPort result) (do (info "Result is channel, content pending...")
                                           (go (ok (<! result))))
            :else                        (ok result)))
        (catch :default e (.done context e nil))))))

(defn azure-queue-function-wrapper
  "wrapper used for timer-triggered function"
  ([handler]
    (azure-queue-function-wrapper handler nil))
  ([handler codec]
    (fn [context message]
      (try 
        (timbre/merge-config! {:appenders {:console nil}})
        (timbre/merge-config! {:appenders {:azure (timbre-appender (.-log context))}})
        (trace (str "message: " (js->clj message)))
        (let [ok     (queue->azure context codec)
              logfn  (.-log context)
              result (handler (into (azure->queue message) {:log logfn}))]

          (cond
            (satisfies? ReadPort result) (do (info "Result is channel, content pending...")
                                            (go (ok (<! result))))
            :else                        (ok result)))
        (catch :default e (.done context e nil))))))