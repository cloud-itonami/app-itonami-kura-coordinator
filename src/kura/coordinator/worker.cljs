(ns kura.coordinator.worker
  "The door. Until now `permissionless` was a property of a design nobody could
  act on: the pricing, the collateral schedule, the epoch settlement and the
  metadata plane all existed as libraries with no way in.

  Two routes, and the honesty of the second is the whole point.

  `GET /operator/quote?tb=N` answers *should I* rather than *can I*. It runs
  `kura.bugyo.collateral/onboarding-quote` — the same function the tests and
  the README use — so the numbers a prospective operator sees are the ones the
  system actually holds, not a marketing table that drifts.

  `POST /operator/register` records an intent. **It does not take a bond and it
  does not place data**, because Phase 0 accepts neither, and an endpoint that
  implied otherwise would be the exact overclaiming the rest of this project
  keeps declining. The response says so in the same breath as the
  confirmation. What it does do is real: the registration is appended to the
  coordinator's event log in the shape `kura.bugyo.plane` replays, so when
  bonding does go live these are not re-keyed by hand.

  There is no authentication on register. That is a deliberate consequence of
  permissionless onboarding plus nothing at stake yet — there is nothing to
  steal and nothing to spend. When a bond is attached, the registration has to
  be signed by the key that posts it, and that is a different endpoint."
  (:require [kura.bugyo.collateral :as collateral]
            [kura.bugyo.plane :as plane]
            [kura.bugyo.pricing :as pricing]))

(defn- json [status body]
  (js/Response. (js/JSON.stringify (clj->js body) nil 2)
                #js {:status status
                     :headers #js {"content-type" "application/json; charset=utf-8"
                                   "cache-control" "no-store"
                                   "access-control-allow-origin" "*"}}))

(def ^:private rates (pricing/rates {}))
(def ^:private pol (collateral/policy {}))
(def ^:private layout pricing/launch-layout)
(def ^:private log-key "coordinator/operator-registry.v1.jsonl")

(defn- quote-for [tb]
  (let [q (collateral/onboarding-quote rates pol layout tb)
        cc (collateral/capital-cost rates pol layout tb)
        earn (pricing/node-earnings rates {:physical-tb tb})]
    {:committed-tb tb
     :bond-usd (:bond-usd q)
     :bond-usd-per-tb (:bond-usd-per-tb q)
     :binding-anchor (name (:binding-anchor q))
     :bond-note (str "Priced against the repair bill, not against what a cheat "
                     "would gain. A strong audit makes deterrence cheap; "
                     "rebuilding what a departed node held is what remains.")
     :epoch-days (get-in rates [:epoch-days :value])
     :epoch-earnings-usd (:total-usd earn)
     :payback-epochs (:payback-epochs q)
     :payback-note (str "The wrong number to decide on — the bond is "
                        "collateral and comes back. Read capital-cost.")
     :capital-cost cc
     :audit-challenges-per-epoch (:audit-challenges-per-epoch q)
     :slash-schedule (:slash-schedule q)
     :tolerated-losses (:tolerated-losses q)
     :rates-provenance
     (into {} (map (fn [[k v]] [k (:provenance v)])) rates)
     :caveat (str "Every rate above is modelled, not observed. Phase 0 exists "
                  "to replace them, and the storage multiplier may tighten "
                  "from 2.0x toward 1.625x when it does — which is a price cut "
                  "to customers, not a change to what you earn per physical TB.")}))

(defn- append> [env record]
  (let [b (.-STATE ^js env)]
    (-> (.get b log-key)
        (.then (fn [o] (if o (.text ^js o) "")))
        (.then (fn [prev]
                 (.put b log-key (str prev (js/JSON.stringify (clj->js record)) "\n"))))
        (.then (fn [_] record)))))

(defn- register> [env body]
  (let [{:keys [node-id contact physical-tb domains]} body
        tb (or physical-tb 0)]
    (cond
      (not (and (string? node-id) (seq node-id)))
      (js/Promise.resolve (json 400 {:error "node-id is required"}))

      (not (and (number? tb) (pos? tb)))
      (js/Promise.resolve (json 400 {:error "physical-tb must be a positive number"}))

      :else
      (let [q (quote-for tb)
            ;; The shape kura.bugyo.plane replays, so these do not get re-keyed
            ;; by hand when bonding goes live.
            event {:kind :node/registered
                   :at (.toISOString (js/Date.))
                   :node (plane/node {:node-id node-id
                                      :physical-tb tb
                                      :bond-usd 0.0
                                      :domains (or domains {})})
                   :contact contact
                   :quoted-bond-usd (:bond-usd q)}]
        (-> (append> env event)
            (.then (fn [_]
                     (json 202
                           {:registered node-id
                            :status "intent recorded"
                            :what-this-does
                            ["appended your registration to the coordinator's event log"
                             "in the shape the metadata plane replays"]
                            :what-this-does-not-do
                            ["no bond has been taken — the escrow contract is not deployed"
                             "no data will be placed on your node — Phase 0 accepts none"
                             "no authentication was required, because nothing is at stake yet;
                              when a bond is attached the registration must be signed by the
                              key that posts it, and that is a different endpoint"]
                            :your-quote q}))))))))

(defn- registry> [env]
  (-> (.get (.-STATE ^js env) log-key)
      (.then (fn [o] (if o (.text ^js o) "")))
      (.then (fn [text]
               (let [lines (remove empty? (.split text "\n"))
                     recs (keep (fn [l] (try (js->clj (js/JSON.parse l) :keywordize-keys true)
                                             (catch :default _ nil)))
                                lines)]
                 (json 200
                       {:registrations (count recs)
                        :committed-tb (reduce + 0 (map #(get-in % [:node :physical-tb] 0) recs))
                        :operators (mapv (fn [r] {:node-id (get-in r [:node :node-id])
                                                  :physical-tb (get-in r [:node :physical-tb])
                                                  :at (:at r)})
                                         recs)
                        :note (str "Public on purpose. A permissionless network whose "
                                   "participant list is private is not one anybody can "
                                   "check the failure-domain spread of.")}))))))

(defn- handle [request env]
  (let [url (js/URL. (.-url request))
        path (.-pathname url)
        method (.-method request)]
    (cond
      (= path "/operator/quote")
      (let [tb (js/parseFloat (or (.get (.-searchParams url) "tb") "10"))]
        (js/Promise.resolve
         (if (and (js/isFinite tb) (pos? tb))
           (json 200 (quote-for tb))
           (json 400 {:error "tb must be a positive number"}))))

      (and (= path "/operator/register") (= method "POST"))
      (-> (.json request)
          (.then (fn [b] (register> env (js->clj b :keywordize-keys true))))
          (.catch (fn [_] (json 400 {:error "expected a JSON body"}))))

      (= path "/operator/registry") (registry> env)

      :else
      (js/Promise.resolve
       (json 200
             {:what "kura coordinator — the operator-facing surface"
              :status (str "Phase 0. Measuring the node-loss rate on rented "
                           "backends. Not accepting customer data and not "
                           "taking bonds.")
              :routes {"GET /operator/quote?tb=N" "what you would bond, earn and be audited on"
                       "POST /operator/register" "record intent — takes no bond, places no data"
                       "GET /operator/registry" "who has registered, publicly"}
              :see-also {"conformance and durability"
                         "https://kura-conformance.04-feasts-minded.workers.dev"
                         "design"
                         "https://github.com/kotoba-lang/kura"}})))))

(def handler
  #js {:fetch (fn [request env _ctx] (handle request env))})
