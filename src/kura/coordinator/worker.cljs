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
  (:require [clojure.string :as str]
            [kura.bugyo.budget :as budget]
            [kura.bugyo.collateral :as collateral]
            [kura.bugyo.plane :as plane]
            [kura.placement :as placement]
            [kura.bugyo.pricing :as pricing]
            [kura.node.crypto-noble :as nc]
            [kura.node.http-fetch :as http]
            [kura.node.s3 :as s3]))

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

(def ^:private budget-policy
  "The operational ceiling, set to $25/month by the owner on 2026-07-30.

  Phase 0 rents its storage and renting loses money by construction, so the
  point of the cap is that the loss is bounded and known rather than
  discovered. $25 buys ~4.17 logical TB mirrored across B2 and owned disk, or
  ~2.08 TB on B2 alone — and the practical limit today is smaller than either,
  because the owned side is a 56 GB slice of a Mac mini."
  (budget/policy {:monthly-usd-cap 25.00}))

(def ^:private b2-host "s3.us-west-004.backblazeb2.com")

(defn- b2-usage>
  "Actual bytes in the B2 bucket, summed from a ListObjectsV2 walk.

  Measured rather than tracked. A counter the coordinator increments is a
  counter that drifts from the bill the moment anything writes outside it —
  and several things do: the conformance probe, the durability run, a manual
  fix. The bucket is the only thing that knows."
  [env]
  (if-not (and (.-B2_KEY_ID ^js env) (.-B2_APP_KEY ^js env))
    (js/Promise.resolve nil)
    (let [crypto (nc/noble-crypto)
          path (s3/object-path "kura-phase0-b2" "")
          q (s3/canonical-query {"list-type" "2" "max-keys" "1000"})
          signed (s3/sign {:crypto crypto
                           :key-id (.-B2_KEY_ID ^js env)
                           :secret (.-B2_APP_KEY ^js env)
                           :region "us-west-004" :service "s3" :host b2-host
                           :method :get :path path :query q
                           :body (js/Uint8Array. 0)
                           :now-iso (http/now-iso)})]
      (-> (s3/-request (http/fetch-http)
                       {:method :get
                        :url (str "https://" b2-host path "?" q)
                        :headers signed
                        :body (js/Uint8Array. 0)})
          (.then (fn [{:keys [status body]}]
                   (when (= 200 status)
                     (let [txt (.decode (js/TextDecoder.) body)
                           sizes (map #(js/parseInt (second %))
                                      (re-seq #"<Size>(\d+)</Size>" txt))]
                       {:objects (count sizes)
                        :bytes (reduce + 0 sizes)
                        :truncated? (some? (re-find #"<IsTruncated>true" txt))}))))
          (.catch (fn [_] nil))))))

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
  (let [{:keys [node-id contact physical-tb domains availability]} body
        tb (or physical-tb 0)
        av (keyword availability)]
    (cond
      (not (and (string? node-id) (seq node-id)))
      (js/Promise.resolve (json 400 {:error "node-id is required"}))

      (not (and (number? tb) (pos? tb)))
      (js/Promise.resolve (json 400 {:error "physical-tb must be a positive number"}))

      ;; Asked rather than assumed, and refused rather than defaulted. Either
      ;; default is a lie about somebody's durability: guess always-on and a
      ;; fleet of laptops reports nines it does not have; guess intermittent and
      ;; rented buckets become unplaceable. The operator is the only one who
      ;; knows whether the machine sleeps, so the operator says.
      (not (contains? placement/availabilities av))
      (js/Promise.resolve
       (json 400 {:error "availability is required"
                  :must-be-one-of ["always-on" "intermittent"]
                  :how-to-choose
                  {"always-on" (str "rented object storage, or a machine that "
                                    "stays powered and reachable — a server, a "
                                    "mini that lives on a shelf")
                   "intermittent" (str "a laptop, a desktop that sleeps, a "
                                       "machine on a connection that comes and "
                                       "goes. The disk being fine is not the "
                                       "question; being reachable is.")}
                  :why (str "The code's tolerance for missing shards is a "
                            "budget. Shards on machines that sleep spend it "
                            "every night, so placement caps how many may sit "
                            "there and keeps every object readable from "
                            "always-on nodes alone. Declaring :intermittent "
                            "costs you nothing — it is not a lower tier. It "
                            "means fewer shards are placed with you, and it "
                            "means you are NOT slashed for being asleep, which "
                            "you would be if you claimed always-on and were "
                            "not.")}))

      :else
      (let [q (quote-for tb)
            ;; The shape kura.bugyo.plane replays, so these do not get re-keyed
            ;; by hand when bonding goes live.
            event {:kind :node/registered
                   :at (.toISOString (js/Date.))
                   :node (plane/node {:node-id node-id
                                      :physical-tb tb
                                      :bond-usd 0.0
                                      :availability av
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

      (= path "/budget")
      ;; The ceiling, against what the bucket actually holds. Reporting a cap
      ;; without the usage beside it is reporting an intention.
      (-> (b2-usage> env)
          (.then (fn [u]
                   (let [usage (if u {:backblaze-b2 (:bytes u)} {})
                         r (budget/report budget-policy rates
                                          pricing/launch-layout usage)]
                     (json 200
                           (merge r
                                  {:b2-measured u
                                   :cap-set-by "owner, 2026-07-30"
                                   :capacity-tb
                                   {:b2-only (budget/capacity-tb budget-policy
                                                                 pricing/launch-layout
                                                                 {:backblaze-b2 1.0})
                                    :b2-plus-owned-mirror
                                    (budget/capacity-tb budget-policy
                                                        pricing/launch-layout
                                                        {:backblaze-b2 0.5 :self-hosted 0.5})}
                                   :note-on-measurement
                                   (str "Usage is summed from the bucket, not from a "
                                        "counter this service keeps. A counter drifts "
                                        "from the bill the moment anything writes "
                                        "outside it, and several things do — the probe, "
                                        "the durability run, a manual fix.")}))))))

      (= path "/pricing")
      ;; The competitive claim, computed rather than typed. The site quotes
      ;; these numbers; this is where they come from, so the page cannot drift
      ;; from the model the way a marketing table does.
      (js/Promise.resolve
       (json 200
             {:launch (pricing/versus-storj rates pricing/launch-layout)
              :target (pricing/versus-storj rates pricing/target-layout)
              :storj-reference pricing/storj-reference
              :node-rates
              (into {} (map (fn [[k v]] [k (select-keys v [:value :provenance :source])]))
                    rates)
              :caveat
              (str "Storj's published figures were read on "
                   (:read-at pricing/storj-reference)
                   " and are a point-in-time reading, not a live feed. Our own "
                   "rates are benchmarked against theirs, not measured on our "
                   "network — Phase 0 exists to replace them.")}))

      :else
      (js/Promise.resolve
       (json 200
             {:what "kura coordinator — the operator-facing surface"
              :status (str "Phase 0. Measuring the node-loss rate on rented "
                           "backends. Not accepting customer data and not "
                           "taking bonds.")
              :routes {"GET /budget" "the $25/month ceiling against what the bucket actually holds"
                       "GET /operator/quote?tb=N" "what you would bond, earn and be audited on"
                       "POST /operator/register" "record intent — takes no bond, places no data"
                       "GET /operator/registry" "who has registered, publicly"}
              :see-also {"conformance and durability"
                         "https://kura-conformance.04-feasts-minded.workers.dev"
                         "design"
                         "https://github.com/kotoba-lang/kura"}})))))

(def handler
  #js {:fetch (fn [request env _ctx] (handle request env))})
