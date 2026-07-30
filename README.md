# kura-coordinator

The door. **https://kura-coordinator.04-feasts-minded.workers.dev**

Until this existed, "permissionless" was a property of a design nobody could
act on: the pricing, the collateral schedule, the epoch settlement and the
metadata plane were all libraries with no way in.

## Routes

### `GET /budget`

The operational spending ceiling — **$25/month, set by the owner 2026-07-30** —
against what the B2 bucket actually holds. Live:

```
b2-measured  {objects 34, bytes 139264}
cost/month   $0.000001   cap $25.00   headroom $25.00
capacity     2.08 TB on B2 alone / 4.17 TB mirrored with owned disk
```

**Usage is summed from the bucket, not from a counter this service keeps.** A
counter drifts from the bill the moment anything writes outside it, and several
things do: the conformance probe, the durability run, a manual fix. The bucket
is the only thing that knows.

Phase 0 rents its storage and renting loses money by construction — B2 is
$6/TB-month against a $1.50/TB-month node payout. The cap exists so the loss is
bounded and known rather than discovered, and `budget/admit-write?` **refuses**
past it rather than warning.

### `GET /operator/quote?tb=N`

Answers *should I*, not *can I*. Runs
`kura.bugyo.collateral/onboarding-quote` — the same function the tests and the
site use — so the numbers a prospective operator sees are the ones the system
actually holds, not a marketing table that drifts from them.

At 50 TB, today's modelled rates:

| | |
|---|---|
| bond | $375 ($7.50/TB, **repair**-bound) |
| earns | $75 per 30-day epoch |
| payback | 5.0 epochs — **the wrong number to decide on** |
| real cost | **4.11%** of revenue at 10% APY, because the bond comes back |
| audit | 688 challenges per epoch |
| tolerates | 13 arbitrary losses |

Every rate carries its `:provenance`, and none is `:observed`.

### `POST /operator/register`

Records intent. **Takes no bond** — the escrow contract is not deployed — and
**places no data**, because Phase 0 accepts none. The response says both in the
same breath as the confirmation, because an endpoint that let you infer
otherwise would be the exact overclaiming the rest of this project keeps
declining.

What it does do is real: the registration is appended to the coordinator's
event log in the shape `kura.bugyo.plane` replays, so these do not get re-keyed
by hand when bonding goes live.

There is **no authentication**, and that is a deliberate consequence of
permissionless onboarding plus nothing at stake yet — there is nothing to steal
and nothing to spend. When a bond is attached, the registration must be signed
by the key that posts it, and that is a different endpoint.

### `GET /operator/registry`

Who has registered, publicly. A permissionless network whose participant list
is private is not one anybody can check the failure-domain spread of — and the
spread is the thing `/audit` on the conformance harness currently says is
insufficient.

## What this Worker decides

Nothing. The economics live in `kura-bugyo`; this adds HTTP and a durable log.
State goes in the same R2 bucket as the Phase 0 measurement, because the
coordinator is one writer and splitting its state across stores is what
ADR-260726 warns about for the query plane and ADR-2607299200 section 4 repeats
for this one.

## License

MIT.
