(ns com.fulcrologic.statecharts.invocation.multiplex
  "Multiplex invocation element constructor. Emits an ordinary `invoke`
   element of type `::mo/multiplex` whose `:params` carry a single
   resolver fn that, when evaluated by the execution model at
   start-invocation time, yields the fully-resolved multiplex
   configuration. Inner per-child fns remain fns inside the resolved
   map and are called by the processor at fan-out time.

   See `multiplex-options` for the option vars and their semantics, and
   `multiplex-processor` for the runtime."
  (:refer-clojure :exclude [count send])
  (:require
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.elements :as e]
    [com.fulcrologic.statecharts.environment :as env]
    [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
    [com.fulcrologic.statecharts.protocols :as sp]))

(defn multiplex
  "Construct a multiplex invocation element. `opts` is a map of:

   - `:id` (required) — invokeid for this multiplex. Must not be `:multiplex`
     (reserved as a child-sid prefix).
   - `:autoforward` (optional) — passes through to the underlying invoke.
   - `mo/child-type` (required) — keyword or `(fn [env data])` returning a
     keyword; the invocation type used for each child.
   - `mo/count` (required) — integer or `(fn [env data])` returning an
     integer; number of children to spawn at start time.
   - `mo/child-params` (optional) — `(fn [env data idx])` returning the
     params map for the child at position `idx`. Defaults to a fn
     returning `{}`.

   Emits an `(invoke …)` element. The element constructor performs only
   presence checks; values that are fns are evaluated by the processor
   at start-invocation time and may depend on the current data model."
  [{:keys [id autoforward] :as opts}]
  (let [user-count        (get opts mo/count)
        user-child-type   (get opts mo/child-type)
        user-child-params (get opts mo/child-params (fn [_ _ _] {}))
        autoforward       (if (contains? opts :autoforward) autoforward true)]
    (assert (some? id)
            "multiplex requires :id")
    (assert (and (or (keyword? id) (string? id))
                 (not= (name id) "multiplex")
                 (not (and (keyword? id)
                           (= (namespace id) "multiplex"))))
            "multiplex :id must not be :multiplex, :multiplex/<x>, or :<y>/multiplex (reserved as child-sid prefix)")
    (assert (some? user-count)
            "multiplex requires mo/count")
    (assert (some? user-child-type)
            "multiplex requires mo/child-type")
    (assert (fn? user-child-params)
            "multiplex mo/child-params must be a function")
    (e/invoke
      (cond-> {:type   mo/type
               :id     id
               :params
               ;; A single resolver fn under mo/config. The lambda
               ;; execution model invokes this with [env data] (or
               ;; 4-arity if explode-event?), and the returned map is
               ;; the resolved config the processor reads. Inner fns
               ;; (like child-params) remain fns within the returned
               ;; map — the execution model only evaluates the
               ;; top-level value.
               {mo/config
                (fn resolve-config
                  ([env data]
                   {mo/child-type   (if (fn? user-child-type)
                                      (user-child-type env data)
                                      user-child-type)
                    mo/count        (if (fn? user-count)
                                      (user-count env data)
                                      user-count)
                    mo/child-params user-child-params})
                  ([env data event-name event-data]
                   {mo/child-type   (if (fn? user-child-type)
                                      (user-child-type env data event-name event-data)
                                      user-child-type)
                    mo/count        (if (fn? user-count)
                                      (user-count env data event-name event-data)
                                      user-count)
                    mo/child-params user-child-params}))}}
        (some? autoforward) (assoc :autoforward autoforward)))))

(defn send
  "Emit a `<send>` element that targets a multiplex cohort. A convenience
   wrapper over `e/send` that hides the `:target` keyword form and the
   `mo/to` routing key.

   `opts`:
   - `:target` (required) — the multiplex's `:id` (keyword). The emitted
     send addresses this session directly; the multiplex processor's
     `forward-event!` consumes it via the autoforward path.
   - `:event` (required) — event name keyword.
   - `:idx`   — integer K to address child K, `:any` to deliver to one
                arbitrary child, or `:all` (default) to broadcast.
   - `:data`  — user data merged into the outgoing event's `:data`.

   The outgoing event's `:data` is `{mo/to <route> ...user-data}`
   (or just the user data when route is `:all`, the default). The
   multiplex processor's `forward-event!` strips `mo/to` and adds
   `mo/from` before delivering to the chosen child(ren)."
  [{:keys [target event idx data] :as _opts}]
  (assert (keyword? target) "mux/send requires a keyword :target (the multiplex :id)")
  (assert (some? event) "mux/send requires :event")
  (let [route       (cond
                      (integer? idx)        {:idx idx}
                      (= idx :any)          :any
                      (or (nil? idx)
                          (= idx :all))     :all
                      :else                 (throw (ex-info "mux/send :idx must be an integer, :any, :all, or nil"
                                                            {:idx idx})))
        merged-data (cond-> (or data {})
                      true              (assoc mo/target-mux target)
                      (not= route :all) (assoc mo/to route))]
    ;; Use :targetexpr (no static :target) to address the CURRENT
    ;; session id at run-time. The event lands on this session's
    ;; external queue, which causes the algorithm's autoforward
    ;; machinery to invoke the multiplex processor's `forward-event!`
    ;; (which strips `mo/to`, adds `mo/from`, and delivers to the
    ;; chosen child(ren)). The `:diagram/label` carries the user-
    ;; visible target for visualization/inspection.
    (e/send {:diagram/label (str "mux/send -> " target)
             :targetexpr    (fn [env _]
                              (:com.fulcrologic.statecharts/session-id
                                (some-> env :com.fulcrologic.statecharts/vwmem deref)))
             :event         event
             :content       (fn [_ _] merged-data)})))

(defn reply
  "Send a reply event from inside a multiplex child statechart to the
   grandparent (the session that declared the multiplex). Reads
   `mo/identity` from the child's data model and merges it into the
   outgoing event data under `mo/from`, so the receiver can target a
   follow-up at this specific child via
   `(mux/send {:target <mux-id> :idx K …})`.

   Call from inside an active script/expression in a child chart that
   the multiplex processor started — i.e. one whose params were seeded
   with `mo/identity`. Throws if the identity is not present."
  [env event-name event-data]
  (let [data        (sp/current-data (::sc/data-model env) env)
        ident       (get data mo/identity)
        grandparent (:grandparent ident)
        queue       (::sc/event-queue env)
        out-data    (assoc event-data mo/from ident)]
    (when-not grandparent
      (throw (ex-info "mux/reply: no grandparent sid in mo/identity"
                      {:identity ident})))
    (sp/send! queue env
              {:target            grandparent
               :type              ::sc/chart
               :source-session-id (env/session-id env)
               :event             event-name
               :data              out-data})
    true))
