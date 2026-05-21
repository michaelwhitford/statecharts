;; Babashka smoke test for com.fulcrologic.statecharts under SCI.
;;
;; Verifies that:
;;   1. The internal promise adapter loads without funcool/promesa.
;;   2. All async-tier namespaces (simple-async, testing-async, the async
;;      processor, the async event loop, etc.) load without promesa.
;;   3. A real chart can be started and an event delivered end-to-end
;;      under bb, with the runtime using only the adapter's native
;;      IPending-backed promises.
;;
;; If anything regresses to a hard promesa dep, this script will fail
;; with a SCI macroexpand error or a ClassNotFound.

(require '[com.fulcrologic.statecharts :as sc]
         '[com.fulcrologic.statecharts.chart :as chart]
         '[com.fulcrologic.statecharts.elements :refer [state transition on-entry script]]
         '[com.fulcrologic.statecharts.events :as evts]
         '[com.fulcrologic.statecharts.simple-async :as simple-async]
         '[com.fulcrologic.statecharts.protocols :as sp]
         '[com.fulcrologic.statecharts.promise :as p]
         '[taoensso.timbre :as log])

;; Quiet logging so output is the bare result.
(log/set-min-level! :warn)

(defn fail! [msg & extras]
  (binding [*out* *err*]
    (apply println "FAIL:" msg extras))
  (System/exit 1))

;; ---------------------------------------------------------------------------
;; Sanity: the adapter selected its native (zero-dep) implementation
;; because promesa is absent.
;; ---------------------------------------------------------------------------

(when (try (require 'promesa.core) true (catch Exception _ false))
  (fail! "promesa loaded on bb — classpath leaked it in (this test must run without promesa)"))

(when (not= :native p/mode)
  (fail! "expected adapter mode :native on bb, got" p/mode))

;; ---------------------------------------------------------------------------
;; Adapter API works on bb (native IPending promises).
;; ---------------------------------------------------------------------------

(when-not (= 2  (p/await! (p/then (p/resolved 1) inc)))                fail! "then chain")
(when-not (= 50 (p/await! (p/then (p/resolved 5) (fn [v] (p/resolved (* v 10))))))
  (fail! "then flatten"))
(when-not (= {:k 1} (p/await! (p/catch (p/rejected (ex-info "x" {:k 1}))
                                       (fn [e] (ex-data e)))))
  (fail! "rejected/catch"))
(when-not (= 3 (p/await! (p/let [a (p/resolved 1) b (p/resolved 2)] (+ a b))))
  (fail! "let macro"))

;; ---------------------------------------------------------------------------
;; End-to-end: real chart, real async processor, real event loop step.
;; ---------------------------------------------------------------------------

(def visits (atom []))

(def demo-chart
  (chart/statechart {:initial :a}
    (state {:id :a}
      (on-entry {} (script {:expr (fn [_ _] (swap! visits conj :a) nil)}))
      (transition {:event :go :target :b}))
    (state {:id :b}
      (on-entry {} (script {:expr (fn [_ _] (swap! visits conj :b) nil)})))))

(def env (simple-async/simple-env))
(sp/register-statechart! (::sc/statechart-registry env) :demo demo-chart)

(def started (sp/start! (::sc/processor env) env :demo {::sc/session-id :s1}))
(def wmem    (if (p/promise? started) (p/await! started) started))

(def after-event
  (sp/process-event! (::sc/processor env) env wmem (evts/new-event {:name :go})))
(def final
  (if (p/promise? after-event) (p/await! after-event) after-event))

(when-not (= [:a :b] @visits)
  (fail! "expected visits [:a :b], got" @visits))
(when-not (= #{:b} (::sc/configuration final))
  (fail! "expected configuration #{:b}, got" (::sc/configuration final)))

(println "OK   bb-version =" (System/getProperty "babashka.version"))
(println "     promesa-on-classpath? =" false)
(println "     visits                =" @visits)
(println "     final-configuration   =" (::sc/configuration final))
