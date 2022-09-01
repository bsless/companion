# io.github.bsless/companion

> "Aah, you were at my side, all along. My true mentor... My guiding moonlight..."

Why are there so many attempts to re-implement Stuart Sierra's Component library?

It's not bad, the principles are sound, the core abstraction is
essentially the same across the entire ecosystem, so why is the Clojure
community constantly reinventing this wheel?

Maybe the problem isn't in the core abstraction, but in the tooling and
development experience surrounding it. What if instead of replacing,
we'll build on top? What's really missing?

- "Please don't make me define records for everything"
- Manipulating and combining subsystems

## Usage

### Don't define more records

Every state needs to be started, stopped, have a way to access it, and maybe use it.

the `component` function receives the following functions:

- `:start` takes the component as an argument and returns the state.
  Since the component is a record, the arguments can be assoc-ed into it.
- `:stop` the opposite of start
- `:step` a function of `[state & args]` for the common use case we
  would want to have for our state. for example, if our state is a
  connection pool, it might be `jdbc/execute`. If it's an Apache Kafka
  producer, it might be `(.send ,,,)`.
  
The returned component does not just implement the Lifecycle protocol, but two other interfaces:

- `IDeref`: want to access the state? Do you really care what key it is
  kept under? just `deref` it.
- `IFn`: The component can also behave as a function, when you invoke
  it, it will call `step` on the state and the arguments.

And that's all you really need to make a usable component. It's less
hassle to define more components like that, encouraging us to build
components which do one thing.

Example:

```clojure
(def c (start (component {:start (fn [_] (atom 0)) :step #(swap! % inc)})))
@c ;; => 0
(c) ;; => 1
@c ;; => 1
```

### Participating in the great (Life)Cycle

Why not make it really easy to participate in the Lifecycle protocol?

`as-component` is useful for stateless components, and just takes
`start`, `stop`, and `init`, where `init` is the initial value to be
passed to `start`.

If you just need a `start` function, you can pass a bare function and `init` is taken to be `{}`.

### Manipulating and combining subsystems

TODO

## License

Copyright © 2022 Ben Sless

Distributed under the Eclipse Public License version 1.0.
