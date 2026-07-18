(ns printbind.governor
  "PrintBindWorkerGovernor — the independent safety/scope layer gating
  every workshop scheduling/logistics proposal an advisor may make for
  a print finishing and binding crew. The governor never dispatches
  hardware itself, never performs print-finishing/binding work on the
  shop floor, and never finalizes a finishing/binding-execution
  decision (e.g. deciding to proceed with a specific
  guillotine-cutting, folding or binding operation) or overrides a
  shop safety officer's judgment — those are permanently out of this
  actor's scope and remain a shop safety officer's exclusive judgment
  (README's 'Robotics premise': this actor coordinates WORKSHOP
  SCHEDULING/LOGISTICS ONLY — it never performs print-finishing/
  binding work itself). Modeled on cloud-itonami-isco-7213's
  sheetmetal.governor (physical-safety-domain shape: blade/guillotine
  hazards and machine-pinch hazards on cutting/folding/binding
  equipment instead of cutting/forming hazards).

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. worker provenance     — the crew member must be independently
                                verified/registered before any action.
    2. workshop provenance   — the workshop must be independently
                                verified/registered before any action.
    3. no-actuation           — proposal :effect must be :propose (the
                                governor never dispatches hardware and
                                never performs print-finishing/binding
                                work itself; it only gates what the
                                advisor may coordinate).
    4. closed op-allowlist    — only :log-work-record,
                                :schedule-crew-operation,
                                :flag-safety-concern and
                                :coordinate-supply-order may ever be
                                proposed; anything else is refused.
    5. scope-excluded action  — any proposal to directly finalize a
                                finishing/binding-execution decision
                                (e.g. deciding to proceed with a
                                specific guillotine-cutting, folding or
                                binding operation), or to override a
                                shop safety officer's judgment, is a
                                hard, permanent block (checked both
                                against the proposed :op and,
                                defense-in-depth, against the
                                proposal's :rationale text — matched as
                                full finalization/execution ACTION
                                phrases such as \"proceed with the
                                guillotine-cutting operation\" /
                                \"authorize the binding operation\" /
                                \"override the shop safety officer's
                                judgment\", never as bare nouns like
                                \"cutting\", \"folding\", \"binding\" or
                                \"safety\", so the check can never
                                self-trip on the advisor's own routine
                                rationale text, e.g. \"logged work
                                record for worker …\" or \"scheduled
                                crew operation for guillotine-cutting
                                task …\" or \"…routed for shop safety
                                officer review\" — all three
                                legitimately contain those bare nouns
                                but none is a finalization action, and
                                all are exercised by
                                `governor-test/default-mock-advisor-proposals-never-self-trip-on-scope-exclusion`).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off
  regardless of confidence):
    6. :op :flag-safety-concern (a blade-hazard / guillotine-hazard /
                                machine-pinch-hazard / equipment-
                                condition concern always escalates to
                                a human, never auto-commits).
    7. :op :coordinate-supply-order above `supply-cost-threshold`.
    8. low confidence (< `confidence-floor`)."
  (:require [clojure.string :as str]
            [printbind.store :as store]))

(def confidence-floor 0.6)
(def supply-cost-threshold 2000)

(def allowed-ops
  #{:log-work-record :schedule-crew-operation
    :flag-safety-concern :coordinate-supply-order})

;; Defense-in-depth: none of these ops are ever in `allowed-ops`
;; above, so they are already refused by the closed-allowlist check
;; below; they are named again here — as explicit finalization/
;; execution ACTIONS, never bare nouns — so a future allowlist edit
;; cannot silently re-open this specific out-of-scope path without
;; also touching this list.
(def ^:private scope-excluded-ops
  #{:finalize-guillotine-cutting-decision :authorize-binding-operation
    :proceed-with-finishing-binding-work :override-safety-officer-judgment
    :override-shop-safety-officer-judgment})

;; Full finalization/execution ACTION phrases only — never bare nouns
;; ("cutting", "folding", "binding", "blade", "guillotine", "safety",
;; "shop safety officer") — so this can never match inside the mock
;; advisor's own default rationale text (which legitimately contains
;; those bare nouns, e.g. "guillotine-cutting task" / "shop safety
;; officer review"). See
;; `governor-test/default-mock-advisor-proposals-never-self-trip-on-scope-exclusion`.
(def ^:private scope-excluded-phrases
  ["proceed with the guillotine-cutting operation" "proceed with the guillotine-cutting work"
   "proceed with the binding operation" "proceed with the folding operation"
   "finalize the guillotine-cutting decision" "finalize the binding decision"
   "authorize the binding operation" "authorize the guillotine-cutting operation"
   "override the shop safety officer's judgment"
   "override the safety officer's judgment"
   "override shop safety officer judgment"
   "override safety officer judgment"])

(defn- contains-excluded-phrase? [s]
  (let [s (str/lower-case (or s ""))]
    (boolean (some #(str/includes? s %) scope-excluded-phrases))))

(defn- hard-violations [proposal worker-record workshop-record]
  (let [{:keys [op rationale]} proposal]
    (cond-> []
      (nil? worker-record)
      (conj {:rule :no-worker
             :detail "未登録 worker への提案は不可（worker record は独立して検証・登録済みでなければならない）"})

      (nil? workshop-record)
      (conj {:rule :no-workshop
             :detail "未登録 workshop への提案は不可（workshop record は独立して検証・登録済みでなければならない）"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation
             :detail "effect は :propose のみ許可（governor は現場作業を直接実行しない）"})

      (not (contains? allowed-ops op))
      (conj {:rule :unknown-op
             :detail (str op " は closed op-allowlist に無い — 提案不可")})

      (or (contains? scope-excluded-ops op) (contains-excluded-phrase? rationale))
      (conj {:rule :scope-excluded-action
             :detail "印刷仕上げ・製本工程の実行判断の確定/shop safety officer の判断の上書きは、この actor の権限外 — 常に永続ブロック"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `printbind.store/Store`. Pure — never mutates
  the store, never dispatches a shop-floor operation."
  [request _context proposal store]
  (let [worker-record (store/worker store (:worker-id request))
        workshop-record (some->> (:workshop-id proposal) (store/workshop store))
        hard (hard-violations proposal worker-record workshop-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        supply-order-over-threshold?
        (and (= :coordinate-supply-order (:op proposal))
             (number? (:cost proposal))
             (> (:cost proposal) supply-cost-threshold))
        always-risky? (or (= :flag-safety-concern (:op proposal))
                           supply-order-over-threshold?)]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
