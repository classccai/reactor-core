/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Fuseable.QueueSubscription;
import reactor.core.publisher.FluxConcatMap.ErrorMode;
import reactor.util.concurrent.QueueSupplier;

/**
 * Maps each upstream value into a Publisher and concatenates them into one
 * sequence of items.
 *
 * @param <T> the source value type
 * @param <R> the output value type
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxMergeSequential<T, R> extends FluxSource<T, R> {

	final ErrorMode errorMode;

	final Function<? super T, ? extends Publisher<? extends R>> mapper;

	final int maxConcurrency;

	final int prefetch;

	final Supplier<Queue<MergeSequentialInner<R>>> queueSupplier;

	FluxMergeSequential(Publisher<? extends T> source,
			Function<? super T, ? extends Publisher<? extends R>> mapper,
			int maxConcurrency, int prefetch, ErrorMode errorMode) {
		this(source, mapper, maxConcurrency, prefetch, errorMode,
				QueueSupplier.get(Math.max(prefetch, maxConcurrency)));
	}

	//for testing purpose
	FluxMergeSequential(Publisher<? extends T> source,
			Function<? super T, ? extends Publisher<? extends R>> mapper,
			int maxConcurrency, int prefetch, ErrorMode errorMode,
			Supplier<Queue<MergeSequentialInner<R>>> queueSupplier) {
		super(source);
		if (prefetch <= 0) {
			throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
		}
		if (maxConcurrency <= 0) {
			throw new IllegalArgumentException("maxConcurrency > 0 required but it was " + maxConcurrency);
		}
		this.mapper = Objects.requireNonNull(mapper, "mapper");
		this.maxConcurrency = maxConcurrency;
		this.prefetch = prefetch;
		this.errorMode = errorMode;
		this.queueSupplier = queueSupplier;
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {
		if (FluxFlatMap.trySubscribeScalarMap(source, s, mapper, false)) {
			return;
		}

		Subscriber<T> parent = new MergeSequentialMain<T, R>(s,
				mapper,
				maxConcurrency,
				prefetch,
				errorMode,
				queueSupplier);
		source.subscribe(parent);
	}

	static final class MergeSequentialMain<T, R>
			implements Subscriber<T>, FluxMergeSequentialSupport<R>, Subscription {

		/** the downstream subscriber */
		final Subscriber<? super R> actual;

		/** the mapper giving the inner publisher for each source value */
		final Function<? super T, ? extends Publisher<? extends R>> mapper;

		/** how many eagerly subscribed inner stream at a time, at most */
		final int maxConcurrency;

		/** request size for inner subscribers (size of the inner queues) */
		final int prefetch;

		final Queue<MergeSequentialInner<R>> subscribers;

		/** whether or not errors should be delayed until the very end of all inner
		 * publishers or just until the completion of the currently merged inner publisher
		 */
		final ErrorMode errorMode;

		Subscription s;

		volatile boolean done;

		volatile boolean cancelled;

		volatile Throwable error;

		static final AtomicReferenceFieldUpdater<MergeSequentialMain, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(MergeSequentialMain.class, Throwable.class, "error");

		MergeSequentialInner<R> current;

		/** guard against multiple threads entering the drain loop. allows thread
		 * stealing by continuing the loop if wip has been incremented externally by
		 * a separate thread. */
		volatile int wip;

		static final AtomicIntegerFieldUpdater<MergeSequentialMain> WIP =
				AtomicIntegerFieldUpdater.newUpdater(MergeSequentialMain.class, "wip");

		volatile long requested;

		static final AtomicLongFieldUpdater<MergeSequentialMain> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(MergeSequentialMain.class, "requested");

		MergeSequentialMain(Subscriber<? super R> actual,
				Function<? super T, ? extends Publisher<? extends R>> mapper,
				int maxConcurrency, int prefetch, ErrorMode errorMode,
				Supplier<Queue<MergeSequentialInner<R>>> queueSupplier) {
			this.actual = actual;
			this.mapper = mapper;
			this.maxConcurrency = maxConcurrency;
			this.prefetch = prefetch;
			this.errorMode = errorMode;
			this.subscribers = queueSupplier.get();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);

				s.request(maxConcurrency == Integer.MAX_VALUE ? Long.MAX_VALUE :
						maxConcurrency);
			}
		}

		@Override
		public void onNext(T t) {
			Publisher<? extends R> publisher;

			try {
				publisher = Objects.requireNonNull(mapper.apply(t), "publisher");
			}
			catch (Throwable ex) {
				onError(Operators.onOperatorError(s, ex, t));
				return;
			}

			MergeSequentialInner<R> inner = new MergeSequentialInner<>(this, prefetch);

			if (cancelled) {
				return;
			}

			if (!subscribers.offer(inner)) {
				int badSize = subscribers.size();
				inner.cancel();
				drainAndCancel();
				onError(Operators.onOperatorError(s,
						new IllegalStateException("Too many subscribers for " +
								"fluxMergeSequential on item: " + t +
								"; subscribers: " + badSize),
						t));
				return;
			}

			if (cancelled) {
				return;
			}

			publisher.subscribe(inner);

			if (cancelled) {
				inner.cancel();
				drainAndCancel();
			}
		}

		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				done = true;
				drain();
			}
			else {
				Operators.onErrorDropped(t);
			}
		}

		@Override
		public void onComplete() {
			done = true;
			drain();
		}

		@Override
		public void cancel() {
			if (cancelled) {
				return;
			}
			cancelled = true;
			s.cancel();

			drainAndCancel();
		}

		void drainAndCancel() {
			if (WIP.getAndIncrement(this) == 0) {
				do {
					cancelAll();
				}
				while (WIP.decrementAndGet(this) != 0);
			}
		}

		void cancelAll() {
			MergeSequentialInner<R> inner;

			while ((inner = subscribers.poll()) != null) {
				inner.cancel();
			}
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.addAndGet(REQUESTED, this, n);
				drain();
			}
		}

		@Override
		public void innerNext(MergeSequentialInner<R> inner, R value) {
			if (inner.queue().offer(value)) {
				drain();
			}
			else {
				inner.cancel();
				innerError(inner, Exceptions.failWithOverflow());
			}
		}

		@Override
		public void innerError(MergeSequentialInner<R> inner, Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				inner.setDone();
				if (errorMode != ErrorMode.END) {
					s.cancel();
				}
				drain();
			}
			else {
				Operators.onErrorDropped(e);
			}
		}

		@Override
		public void innerComplete(MergeSequentialInner<R> inner) {
			inner.setDone();
			drain();
		}

		@Override
		public void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}

			int missed = 1;
			MergeSequentialInner<R> inner = current;
			Subscriber<? super R> a = actual;
			ErrorMode em = errorMode;

			for (; ; ) {
				long r = requested;
				long e = 0L;

				if (inner == null) {

					if (em != ErrorMode.END) {
						Throwable ex = error;
						if (ex != null) {
							cancelAll();

							a.onError(ex);
							return;
						}
					}

					boolean outerDone = done;

					inner = subscribers.poll();

					if (outerDone && inner == null) {
						Throwable ex = error;
						if (ex != null) {
							a.onError(ex);
						}
						else {
							a.onComplete();
						}
						return;
					}

					if (inner != null) {
						current = inner;
					}
				}

				boolean continueNextSource = false;

				if (inner != null) {
					Queue<R> q = inner.queue();
					if (q != null) {
						while (e != r) {
							if (cancelled) {
								cancelAll();
								return;
							}

							if (em == ErrorMode.IMMEDIATE) {
								Throwable ex = error;
								if (ex != null) {
									current = null;
									inner.cancel();
									cancelAll();

									a.onError(ex);
									return;
								}
							}

							boolean d = inner.isDone();

							R v;

							try {
								v = q.poll();
							}
							catch (Throwable ex) {
								current = null;
								inner.cancel();
								ex = Operators.onOperatorError(ex);
								cancelAll();
								a.onError(ex);
								return;
							}

							boolean empty = v == null;

							if (d && empty) {
								inner = null;
								current = null;
								s.request(1);
								continueNextSource = true;
								break;
							}

							if (empty) {
								break;
							}

							a.onNext(v);

							e++;

							inner.requestOne();
						}

						if (e == r) {
							if (cancelled) {
								cancelAll();
								return;
							}

							if (em == ErrorMode.IMMEDIATE) {
								Throwable ex = error;
								if (ex != null) {
									current = null;
									inner.cancel();
									cancelAll();

									a.onError(ex);
									return;
								}
							}

							boolean d = inner.isDone();

							boolean empty = q.isEmpty();

							if (d && empty) {
								inner = null;
								current = null;
								s.request(1);
								continueNextSource = true;
							}
						}
					}
				}

				if (e != 0L && r != Long.MAX_VALUE) {
					REQUESTED.addAndGet(this, -e);
				}

				if (continueNextSource) {
					continue;
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}
	}

	/**
	 * Represents the inner flux in a mergeSequential, that has an internal queue to
	 * hold items while they arrive out of order. The queue is drained as soon as correct
	 * order can be restored.
	 * @param <R> the type of objects emitted by the inner flux
	 */
	static final class MergeSequentialInner<R> implements Subscriber<R>{

		final FluxMergeSequentialSupport<R> parent;

		final int prefetch;

		final int limit;

		volatile Queue<R> queue;

		volatile Subscription subscription;

		static final AtomicReferenceFieldUpdater<MergeSequentialInner, Subscription>
				SUBSCRIPTION = AtomicReferenceFieldUpdater.newUpdater(
						MergeSequentialInner.class, Subscription.class, "subscription");

		volatile boolean done;

		long produced;

		int fusionMode;

		MergeSequentialInner(FluxMergeSequentialSupport<R> parent, int prefetch) {
			this.parent = parent;
			this.prefetch = prefetch;
			this.limit = prefetch - (prefetch >> 2);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(SUBSCRIPTION, this, s)) {
				if (s instanceof QueueSubscription) {
					@SuppressWarnings("unchecked")
					QueueSubscription<R> qs = (QueueSubscription<R>) s;

					int m = qs.requestFusion(Fuseable.ANY | Fuseable.THREAD_BARRIER);
					if (m == Fuseable.SYNC) {
						fusionMode = m;
						queue = qs;
						done = true;
						parent.innerComplete(this);
						return;
					}
					if (m == Fuseable.ASYNC) {
						fusionMode = m;
						queue = qs;
						//FIXME could be mutualized in DrainUtils or Operators (+ review other prefetch based operators)
						s.request(prefetch == Integer.MAX_VALUE ? Long.MAX_VALUE : prefetch);
						return;
					}
				}

				queue = QueueSupplier.<R>get(prefetch).get();
				s.request(prefetch == Integer.MAX_VALUE ? Long.MAX_VALUE : prefetch);
			}
		}

		@Override
		public void onNext(R t) {
			if (fusionMode == Fuseable.NONE) {
				parent.innerNext(this, t);
			} else {
				parent.drain();
			}
		}

		@Override
		public void onError(Throwable t) {
			parent.innerError(this, t);
		}

		@Override
		public void onComplete() {
			parent.innerComplete(this);
		}

		void requestOne() {
			if (fusionMode != Fuseable.SYNC) {
				long p = produced + 1;
				if (p == limit) {
					produced = 0L;
					subscription.request(p);
				} else {
					produced = p;
				}
			}
		}


		void cancel() {
			Operators.set(SUBSCRIPTION, this, Operators.cancelledSubscription());
		}

		public boolean isDone() {
			return done;
		}

		public void setDone() {
			this.done = true;
		}

		public Queue<R> queue() {
			return queue;
		}
	}

	interface FluxMergeSequentialSupport<T> {

		void innerNext(MergeSequentialInner<T> inner, T value);

		void innerComplete(MergeSequentialInner<T> inner);

		void innerError(MergeSequentialInner<T> inner, Throwable e);

		void drain();
	}

}