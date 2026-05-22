(ns com.fulcrologic.statecharts.invocation.multiplex-options
  "Option vars for the multiplex invocation processor. Each var is a
   namespaced keyword with a docstring — RAD-style, so authors get
   navigable documentation for every wire and declaration key.

   Group 1: Declaration options used on the `(multiplex {...})` map.
   Group 2: Wire keys used in event data and in seeded child params.

   The keyword values live under
   `com.fulcrologic.statecharts.invocation.multiplex/<name>`."
  (:refer-clojure :exclude [count identity type]))

(def type
  "The invocation type for multiplex. Use as `:type` on the invoke element,
   or to register/match the multiplex processor."
  :com.fulcrologic.statecharts.invocation.multiplex/type)

(def child-type
  "REQUIRED on `(multiplex {...})`. The underlying invocation type for each
   child. May be a keyword (e.g. `::sc/chart`) or a `(fn [env data])` /
   `(fn [env data event-name event-data])` returning a keyword. Evaluated
   once at start-invocation time."
  :com.fulcrologic.statecharts.invocation.multiplex/child-type)

(def count
  "REQUIRED on `(multiplex {...})`. Number of children to spawn. May be an
   integer or a `(fn [env data])` / `(fn [env data event-name event-data])`
   returning an integer. Evaluated once at start-invocation time."
  :com.fulcrologic.statecharts.invocation.multiplex/count)

(def child-params
  "OPTIONAL on `(multiplex {...})`. `(fn [env data idx])` returning the
   params map handed to the underlying processor for child at position
   `idx`. Defaults to `(fn [_ _ _] {})`. Called per-child at fan-out time;
   read `:_event` from `data` if event context is needed."
  :com.fulcrologic.statecharts.invocation.multiplex/child-params)

(def config
  "INTERNAL. The single `:params` key the element constructor uses to
   pack the multiplex's resolved configuration. Surfaced for testing /
   advanced use; user charts should not need to reference this directly."
  :com.fulcrologic.statecharts.invocation.multiplex/config)

(def to
  "Routing directive on parent → cohort events. Set in event `:data`.
   Values: `:all` (default — broadcast), `:any` (one arbitrary child),
   or `{:idx K}` (specific child). Consumed by the multiplex processor's
   `forward-event!` and stripped before delivery to the chosen child(ren)."
  :com.fulcrologic.statecharts.invocation.multiplex/to)

(def from
  "Origin tag added to events that cross the multiplex boundary. Shape
   depends on direction:

   - Parent → child (added by `forward-event!`): `{:sid <grandparent-sid>}`.
   - Child → parent (added by `mux/reply`):
     `{:sid <child-sid> :idx <K> :invokeid <mux-id> :grandparent <gp-sid>}`.

   Receivers use this to address a follow-up at the specific child via
   `mo/to {:idx K}`."
  :com.fulcrologic.statecharts.invocation.multiplex/from)

(def target-mux
  "Discriminator on parent → cohort events that identifies WHICH multiplex
   in a parent state should process the event. Set automatically by
   `mux/send`. When present, only the multiplex whose `:id` matches the
   value processes the event; other multiplexes in the same parent state
   no-op. When absent, all active multiplexes process (legacy behavior
   for raw `<send>` callers). Stripped from event data before delivery
   to the chosen child(ren)."
  :com.fulcrologic.statecharts.invocation.multiplex/target-mux)

(def identity
  "Seeded into each child's params at start. Shape:
   `{:sid <child-sid> :idx <K> :invokeid <mux-id> :grandparent <sid>}`.
   For statechart children, available in the child's data model under
   this key — used by `mux/reply` to annotate outgoing events and to
   address the grandparent directly. Non-statechart child processors
   may use it however they wish."
  :com.fulcrologic.statecharts.invocation.multiplex/identity)
