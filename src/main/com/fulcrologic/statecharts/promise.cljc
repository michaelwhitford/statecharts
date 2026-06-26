(ns com.fulcrologic.statecharts.promise
  "Promise abstraction used internally by statecharts' async code paths.

   ## Strategy

   Choice is made at namespace-load time (and macro-expansion time, for
   the CLJ side), with two distinct modes:

   * **Promesa available** (typical CLJ deployments) — every function and
     macro in this namespace is a thin alias for the corresponding
     `promesa.core` symbol. We emit *real* promesa promises directly, so
     any consumer code that mixes our alias with `promesa.core` is
     trivially interoperable: there is only one promise type at runtime,
     and no bridge code is needed.

   * **Promesa absent** (notably babashka, whose SCI cannot load promesa)
     — we use a zero-dep native implementation backed by
     `clojure.core/promise` (`clojure.lang.IPending`) on JVM/bb.

   CLJS always uses the native `js/Promise`-backed implementation. That
   interoperates with promesa-cljs's `PromiseImpl` through the standard
   JS Thenable contract (`.then`/`.catch`) — both libraries follow the
   same protocol and js/Promise's spec-mandated auto-unwrapping of
   thenable return values does the rest.

   The 6-function public API matches `promesa.core` exactly for
   `then`/`catch`/`let`/`promise?`/`create`/`rejected`, plus
   `resolved`/`wrap`/`do!`/`await!` for completeness."
  (:refer-clojure :exclude [let catch])
  #?(:cljs (:require-macros [com.fulcrologic.statecharts.promise])))

;; ============================================================================
;; Compile-time / load-time detection of promesa on the JVM classpath. The
;; macro side runs in CLJ for both CLJ and CLJS compilation targets, so this
;; one decision drives both.
;; ============================================================================

#?(:clj
   (def ^:private promesa-available?
     (try
       (require 'promesa.core)
       true
       (catch Throwable _ false))))

;; Fail fast on a present-but-too-old promesa. `promesa.core/await!` was added
;; in 10.0.570; older versions (e.g. 8.0.450) load fine but lack it, so the
;; `(intern ... (deref (resolve 'promesa.core/await!)))` below blows up while
;; this namespace is loading. The resulting failure is cryptic and far from the
;; root cause (e.g. under shadow-cljs it surfaces as a macro-loading error like
;; `No namespace: com.fulcrologic.statecharts.promise found`). Silently falling
;; back to native mode would also be unsafe: native `promise?` is
;; `(instance? IPending v)`, but a real promesa JVM promise is a CompletionStage
;; (NOT IPending), so async expressions returning promesa promises would be
;; mis-handled by the engine. So this is a hard error with an actionable message.
#?(:clj
   (when (and promesa-available?
              (nil? (resolve 'promesa.core/await!)))
     (throw (ex-info
              (str "An outdated funcool/promesa is on the classpath: "
                   "com.fulcrologic.statecharts.promise requires promesa.core/await!, "
                   "which was added in promesa 10.0.570. Upgrade funcool/promesa to "
                   ">= 10.0.570 (or remove it from the classpath entirely to use the "
                   "native, promesa-free fallback).")
              {:missing-promesa-sym 'promesa.core/await!
               :min-promesa-version "10.0.570"}))))

#?(:clj
   (def ^:no-doc mode
     "One of `:promesa` or `:native`. CLJ runtime only. Useful for tests
      that need to know which implementation backs the namespace."
     (if promesa-available? :promesa :native)))

;; ============================================================================
;; CLJS — always native (js/Promise). promesa-cljs's PromiseImpl interops
;; naturally via the JS Thenable contract; nothing extra needed.
;; ============================================================================

#?(:cljs
   (defn promise?
     "True if `v` is a thenable — a `js/Promise`, promesa-cljs's
      `PromiseImpl`, or anything else exposing a `.then` function (the
      standard JS thenable contract)."
     [v]
     (and (some? v)
          (instance? js/Object v)
          (fn? (.-then v)))))

#?(:cljs (defn resolved [v]   (.resolve js/Promise v)))
#?(:cljs (defn rejected [ex]  (.reject  js/Promise ex)))
#?(:cljs (defn create   [f]   (js/Promise. (fn [res rej] (f res rej)))))
#?(:cljs (defn then     [p f] (.then  p f)))
#?(:cljs (defn catch    [p f] (.catch p f)))
#?(:cljs (defn wrap     [v]   (if (promise? v) v (resolved v))))

;; ============================================================================
;; CLJ — choose at namespace-load time
;; ============================================================================

;; ---- Promesa delegation mode ------------------------------------------------
;;
;; Every primitive is a direct alias for the corresponding `promesa.core`
;; symbol. (def x other-ns/y) copies the function value, so call-site
;; performance is identical to calling promesa.core directly.

#?(:clj
   (when promesa-available?
     ;; promesa.core/let and /do! are macros — we re-export them below
     ;; as macros that simply forward, so the existing `(p/let ...)` /
     ;; `(p/do! ...)` call sites keep working transparently.
     (intern *ns*
       (with-meta 'promise?
         {:doc "True if `v` is a promesa promise (CompletionStage on JVM)."
          :arglists '([v])})
       (deref (resolve 'promesa.core/promise?)))
     (intern *ns* 'resolved (deref (resolve 'promesa.core/resolved)))
     (intern *ns* 'rejected (deref (resolve 'promesa.core/rejected)))
     (intern *ns* 'create   (deref (resolve 'promesa.core/create)))
     (intern *ns* 'then     (deref (resolve 'promesa.core/then)))
     (intern *ns* 'catch    (deref (resolve 'promesa.core/catch)))
     (intern *ns* 'wrap     (deref (resolve 'promesa.core/wrap)))
     (intern *ns* 'await!   (deref (resolve 'promesa.core/await!)))))

;; ---- Native mode -----------------------------------------------------------
;;
;; Backed by clojure.core/promise (any IPending). Rejections are threaded
;; through a Rejection sentinel record carried as the delivered value.

#?(:clj (defrecord Rejection [error]))
#?(:clj (defn rejection? [v] (instance? Rejection v)))

#?(:clj
   (when-not promesa-available?
     (intern *ns*
       (with-meta 'promise?
         {:doc "True if `v` is a clojure.core/promise (any IPending)."
          :arglists '([v])})
       (fn [v] (instance? clojure.lang.IPending v)))

     (intern *ns* 'resolved
       (fn [v] (doto (clojure.core/promise) (deliver v))))

     (intern *ns* 'rejected
       (fn [ex] (doto (clojure.core/promise) (deliver (->Rejection ex)))))

     (intern *ns* 'create
       (fn [f]
         (clojure.core/let [p (clojure.core/promise)
                            r! (fn [v] (deliver p v))
                            j! (fn [e] (deliver p (->Rejection e)))]
           (f r! j!)
           p)))

     (intern *ns* '-await-value
       (fn await-value [v]
         (clojure.core/loop [v v]
           (if (clojure.core/and (instance? clojure.lang.IPending v)
                                 (not (rejection? v)))
             (recur (clojure.core/deref v))
             v))))

     (intern *ns* 'then
       (fn [prom f]
         (clojure.core/let [await-value (resolve 'com.fulcrologic.statecharts.promise/-await-value)
                            result      (clojure.core/promise)]
           (clojure.core/future
             (clojure.core/let [v (await-value prom)]
               (if (rejection? v)
                 (deliver result v)
                 (try
                   (clojure.core/let [r  (f v)
                                      r' (await-value r)]
                     (deliver result r'))
                   (catch Throwable e
                     (deliver result (->Rejection e)))))))
           result)))

     (intern *ns* 'catch
       (fn [prom f]
         (clojure.core/let [await-value (resolve 'com.fulcrologic.statecharts.promise/-await-value)
                            result      (clojure.core/promise)]
           (clojure.core/future
             (clojure.core/let [v (await-value prom)]
               (if (rejection? v)
                 (try
                   (clojure.core/let [r  (f (:error v))
                                      r' (await-value r)]
                     (deliver result r'))
                   (catch Throwable e
                     (deliver result (->Rejection e))))
                 (deliver result v))))
           result)))

     (intern *ns* 'wrap
       (fn [v]
         (if ((resolve 'com.fulcrologic.statecharts.promise/promise?) v)
           v
           ((resolve 'com.fulcrologic.statecharts.promise/resolved) v))))

     (intern *ns* 'await!
       (fn [prom]
         (if (instance? clojure.lang.IPending prom)
           (clojure.core/let [await-value (resolve 'com.fulcrologic.statecharts.promise/-await-value)
                              v           (await-value prom)]
             (if (rejection? v) (throw (:error v)) v))
           prom)))))

;; ============================================================================
;; Macros — share the same compile-time decision
;;
;; `(:ns &env)` is truthy when expanding into CLJS code. CLJS is always
;; native (js/Promise). CLJ uses promesa.core/let when promesa is on the
;; compile-time classpath, otherwise our native then-chain expansion.
;; ============================================================================

#?(:clj
   (defmacro let
     "Sequential let where each binding RHS may be a promise; chains via
      `then`. Expands to `promesa.core/let` when promesa is on the
      compile-time classpath (CLJ only); otherwise expands to a recursive
      `then` chain over the native impl."
     [bindings & body]
     (assert (even? (count bindings))
       "com.fulcrologic.statecharts.promise/let requires an even number of binding forms")
     (cond
       (clojure.core/and promesa-available? (not (:ns &env)))
       `(promesa.core/let ~bindings ~@body)

       (empty? bindings)
       `(do ~@body)

       :else
       (clojure.core/let [[sym val & more] bindings]
         `(then ~val
            (fn [~sym]
              (com.fulcrologic.statecharts.promise/let [~@more] ~@body)))))))

#?(:clj
   (defmacro do!
     "Evaluate `body` and return a promise of its value, capturing any
      sync throw as a rejection. Forwards to `promesa.core/do!` when
      promesa is on the CLJ classpath; otherwise wraps body in a
      try/catch and lifts via our `resolved` / `rejected`."
     [& body]
     (cond
       (clojure.core/and promesa-available? (not (:ns &env)))
       `(promesa.core/do! ~@body)

       (:ns &env)
       `(try (resolved (do ~@body))
             (catch :default e# (rejected e#)))

       :else
       `(try (resolved (do ~@body))
             (catch Throwable e# (rejected e#))))))
