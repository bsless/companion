(ns bsless.companion
  (:require
   [clojure.pprint :refer [simple-dispatch]]
   [com.stuartsierra.component
    :as component
    :refer [start stop Lifecycle map->SystemMap system-map using]])
  (:import
   (clojure.lang IMeta)))

(defn imeta?
  [c]
  (instance? IMeta c))

(defn as-component
  "Taking a function `start`, and optionally an initial value `init` and
  function `stop`, returns a map or `init` which behaves like a
  component with `start` and `stop`."
  {:arglists
   '([{:keys [init start stop]
       :or {stop identity
            init {}}}]
     [start])}
  ([spec]
   (let [{:keys [init start stop]
          :or {stop identity
               init {}}}
         (cond (map? spec) spec (fn? spec) {:start spec})]
     (assert (imeta? init) "Component wrapper must be an IMeta")
     (with-meta
       (or init {})
       {'com.stuartsierra.component/start
        (fn -start [this]
          (let [ret (start this)]
            (cond-> ret
              (imeta? ret)
              (with-meta
                {'com.stuartsierra.component/stop
                 (fn -stop [this]
                   (stop this))}))))}))))

(comment
  (-> {:init {}
       :start (fn [x] (println 'start x) (assoc x :state 1))
       :stop (fn [x] (println 'stop x) x)}
      as-component
      start
      stop))

(prefer-method print-method java.util.Map clojure.lang.IDeref)
(prefer-method print-method clojure.lang.IRecord clojure.lang.IDeref)
(prefer-method print-dup java.util.Map clojure.lang.IDeref)
(prefer-method print-dup clojure.lang.IRecord clojure.lang.IDeref)
(prefer-method simple-dispatch java.util.Map clojure.lang.IDeref)
(prefer-method simple-dispatch clojure.lang.IRecord clojure.lang.IDeref)
(prefer-method simple-dispatch clojure.lang.IPersistentMap clojure.lang.IDeref)

(defrecord StateComponent [__state __step __start __stop __get]
  Lifecycle
  (start [this] (assoc this :__state (__start this)))
  (stop [this] (assoc this :__state (__stop __state)))
  clojure.lang.IDeref
  (deref [_] (__get __state))
  clojure.lang.IFn
  (invoke [_] (__step __state))
  (invoke [_ a] (__step __state a))
  (invoke [_ a b] (__step __state a b))
  (invoke [_ a b c] (__step __state a b c))
  (invoke [_ a b c d] (__step __state a b c d))
  (invoke [_ a b c d e] (__step __state a b c d e))
  (applyTo [_ arglist] (apply __step __state arglist)))


(defn component
  "Return a stateful component that:
  Initializes its state with:
  `start` :: (fn [this]) -> state
  Stops its state with:
  `stop` :: (fn [state]) -> state
  Can be invoked like a function and calls `step` with `state` and additional args:
  `step` :: (fn [state & args]) -> Effect
  And can be `deref`ed to get its state.

  Example:
  (def c (start (component {:start (fn [_] (atom 0)) :step #(swap! % inc)})))
  @c ;; => 0
  (c) ;; => 1
  @c ;; => 1"
  [{:keys [start stop step get]
    :or {start (fn [& args] args)
         stop identity
         step identity
         get identity}
    :as opts}]
  (assert (ifn? start))
  (assert (ifn? stop))
  (assert (ifn? step))
  (merge
   (map->StateComponent {:__step step :__start start :__stop stop :__get get})
   (dissoc opts :start :stop :step :get)))

(defn into-system
  "Takes any number of system/map-like systems and collects them in a
  single system.
  Handling name collisions is the caller's responsibility."
  [& systems]
  (into (map->SystemMap {}) cat systems))

(defn merge-systems
  [s1 s2]
  (into-system s1 s2))

(defn configure-system
  [system configuration]
  (map->SystemMap (merge-with merge system configuration)))

(defn- qualify-kw
  [k with]
  (keyword with (clojure.core/name k)))

(defn qualify-using
  [component with system]
  (reduce-kv
   (fn [m k v]
     (assoc m k (cond-> v (contains? system v) (qualify-kw with))))
   component component))

(defn qualify-component
  "Taking a `prefix` and system `sys`, return a function from key and
  component to a qualified key and component with correctly qualified
  dependencies."
  [prefix sys]
  (fn [[k v]]
    [(qualify-kw k prefix)
     (vary-meta v update ::component/dependencies #(qualify-using % prefix sys))]))

(defn qualify-system
  "Qualify all the components in `sys` with `prefix`.
  :a -> :prefix/a
  Dependencies are qualified if they exist in the source system."
  [sys prefix]
  (into-system (map (qualify-component (name prefix) sys) sys)))

(comment
  (qualify-system
   (system-map
    :a (using {} [:b])
    :b {})
   "foo"))
