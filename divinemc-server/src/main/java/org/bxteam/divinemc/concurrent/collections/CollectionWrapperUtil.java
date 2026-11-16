package org.bxteam.divinemc.concurrent.collections;

import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongListIterator;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CollectionWrapperUtil {
    private CollectionWrapperUtil() {
        throw new IllegalStateException("This class cannot be instantiated");
    }

    private static <T> Int2ObjectMap.@NotNull Entry<T> intEntryForwards(Map.Entry<Integer, T> entry) {
        return new Int2ObjectMap.Entry<>() {
            @Override
            public T getValue() {
                return entry.getValue();
            }

            @Override
            public T setValue(T value) {
                return entry.setValue(value);
            }

            @Override
            public int getIntKey() {
                return entry.getKey();
            }

            @Override
            public boolean equals(Object obj) {
                if (obj == entry) {
                    return true;
                }
                return super.equals(obj);
            }

            @Override
            public int hashCode() {
                return entry.hashCode();
            }
        };
    }

    private static <T> Map.Entry<Integer, T> intEntryBackwards(Int2ObjectMap.Entry<T> entry) {
        return entry;
    }

    public static <T> ObjectSet<Int2ObjectMap.Entry<T>> entrySetIntWrap(Map<Integer, T> map) {
        return new ConvertingObjectSet<>(
            map.entrySet(),
            CollectionWrapperUtil::intEntryForwards,
            CollectionWrapperUtil::intEntryBackwards
        );
    }

    public static IntSet wrapIntSet(Set<Integer> intset) {
        return new WrappingIntSet(intset);
    }

    public static ByteIterator itrByteWrap(Iterator<Byte> backing) {
        return new WrappingByteIterator(backing);
    }

    public static ByteIterator itrByteWrap(Iterable<Byte> backing) {
        return itrByteWrap(backing.iterator());
    }

    public static IntIterator itrIntWrap(Iterator<Integer> backing) {
        return new WrappingIntIterator(backing);
    }

    public static IntIterator itrIntWrap(Iterable<Integer> backing) {
        return itrIntWrap(backing.iterator());
    }

    public static LongIterator itrLongWrap(Iterator<Long> backing) {
        return new WrappingLongIterator(backing);
    }

    public static LongIterator itrLongWrap(Iterable<Long> backing) {
        return itrLongWrap(backing.iterator());
    }

    public static ShortIterator itrShortWrap(Iterator<Short> backing) {
        return new WrappingShortIterator(backing);
    }

    public static ShortIterator itrShortWrap(Iterable<Short> backing) {
        return itrShortWrap(backing.iterator());
    }

    public static LongListIterator wrap(ListIterator<Long> c) {
        return new WrappingLongListIterator(c);
    }

    public static LongListIterator wrap(Iterator<Long> c) {
        return new SlimWrappingLongListIterator(c);
    }

    public static <K> ObjectCollection<K> wrap(Collection<K> c) {
        return new WrappingObjectCollection<>(c);
    }

    public static <K> ReferenceSet<K> wrap(Set<K> s) {
        return new WrappingRefSet<>(s);
    }

    public static <T> ObjectIterator<T> itrWrap(Iterable<T> in) {
        return new WrapperObjectIterator<>(in.iterator());
    }

    public static class ConvertingObjectSet<E, T> implements ObjectSet<T> {
        private final Set<E> backing;
        private final Function<E, T> forward;
        private final Function<T, E> back;

        public ConvertingObjectSet(Set<E> backing, Function<E, T> forward, Function<T, E> back) {
            this.backing = Objects.requireNonNull(backing, "Backing set cannot be null");
            this.forward = Objects.requireNonNull(forward, "Forward function cannot be null");
            this.back = Objects.requireNonNull(back, "Backward function cannot be null");
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean contains(Object o) {
            try {
                return backing.contains(back.apply((T) o));
            } catch (ClassCastException cce) {
                return false;
            }
        }

        @Override
        public Object[] toArray() {
            return backing.stream().map(forward).toArray();
        }

        @Override
        public <R> R[] toArray(R[] a) {
            return backing.stream().map(forward).collect(Collectors.toSet()).toArray(a);
        }

        @Override
        public boolean add(T e) {
            return backing.add(back.apply(e));
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean remove(Object o) {
            try {
                return backing.remove(back.apply((T) o));
            } catch (ClassCastException cce) {
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean containsAll(Collection<?> c) {
            try {
                return backing.containsAll(c.stream()
                    .map(i -> back.apply((T) i))
                    .collect(Collectors.toSet()));
            } catch (ClassCastException cce) {
                return false;
            }
        }

        @Override
        public boolean addAll(Collection<? extends T> c) {
            return backing.addAll(c.stream().map(back).collect(Collectors.toSet()));
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean removeAll(Collection<?> c) {
            try {
                return backing.removeAll(c.stream()
                    .map(i -> back.apply((T) i))
                    .collect(Collectors.toSet()));
            } catch (ClassCastException cce) {
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean retainAll(Collection<?> c) {
            try {
                return backing.retainAll(c.stream()
                    .map(i -> back.apply((T) i))
                    .collect(Collectors.toSet()));
            } catch (ClassCastException cce) {
                return false;
            }
        }

        @Override
        public void clear() {
            backing.clear();
        }

        @Override
        public ObjectIterator<T> iterator() {
            return new ObjectIterator<>() {
                private final Iterator<E> backg = backing.iterator();

                @Override
                public boolean hasNext() {
                    return backg.hasNext();
                }

                @Override
                public T next() {
                    return forward.apply(backg.next());
                }

                @Override
                public void remove() {
                    backg.remove();
                }
            };
        }
    }

    static class WrappingIntIterator implements IntIterator {
        private final Iterator<Integer> backing;

        WrappingIntIterator(Iterator<Integer> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public boolean hasNext() {
            return backing.hasNext();
        }

        @Override
        public int nextInt() {
            return backing.next();
        }

        @Override
        public Integer next() {
            return backing.next();
        }

        @Override
        public void remove() {
            backing.remove();
        }
    }

    static class WrappingLongIterator implements LongIterator {
        private final Iterator<Long> backing;

        WrappingLongIterator(Iterator<Long> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public boolean hasNext() {
            return backing.hasNext();
        }

        @Override
        public long nextLong() {
            return backing.next();
        }

        @Override
        public Long next() {
            return backing.next();
        }

        @Override
        public void remove() {
            backing.remove();
        }
    }

    static class WrappingShortIterator implements ShortIterator {
        private final Iterator<Short> backing;

        WrappingShortIterator(Iterator<Short> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public boolean hasNext() {
            return backing.hasNext();
        }

        @Override
        public short nextShort() {
            return backing.next();
        }

        @Override
        public Short next() {
            return backing.next();
        }

        @Override
        public void remove() {
            backing.remove();
        }
    }

    static class WrappingByteIterator implements ByteIterator {
        private final Iterator<Byte> backing;

        WrappingByteIterator(Iterator<Byte> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public boolean hasNext() {
            return backing.hasNext();
        }

        @Override
        public byte nextByte() {
            return next();
        }

        @Override
        public Byte next() {
            return backing.next();
        }

        @Override
        public void remove() {
            backing.remove();
        }
    }

    public static class WrappingIntSet implements IntSet {
        private final Set<Integer> backing;

        public WrappingIntSet(Set<Integer> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public boolean add(int key) {
            return backing.add(key);
        }

        @Override
        public boolean contains(int key) {
            return backing.contains(key);
        }

        @Override
        public int[] toIntArray() {
            return backing.stream().mapToInt(Integer::intValue).toArray();
        }

        @Override
        public int[] toArray(int[] a) {
            return ArrayUtils.toPrimitive(backing.toArray(new Integer[0]));
        }

        @Override
        public boolean addAll(IntCollection c) {
            return backing.addAll(c);
        }

        @Override
        public boolean containsAll(IntCollection c) {
            return backing.containsAll(c);
        }

        @Override
        public boolean removeAll(IntCollection c) {
            return backing.removeAll(c);
        }

        @Override
        public boolean retainAll(IntCollection c) {
            return backing.retainAll(c);
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }

        @Override
        public Object[] toArray() {
            return backing.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return backing.toArray(a);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return backing.containsAll(c);
        }

        @Override
        public boolean addAll(Collection<? extends Integer> c) {
            return backing.addAll(c);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            return backing.removeAll(c);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return backing.retainAll(c);
        }

        @Override
        public void clear() {
            backing.clear();
        }

        @Override
        public IntIterator iterator() {
            return new WrappingIntIterator(backing.iterator());
        }

        @Override
        public boolean remove(int k) {
            return backing.remove(k);
        }
    }

    public static class WrappingRefSet<V> implements ReferenceSet<V> {
        private final Set<V> backing;

        public WrappingRefSet(Set<V> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public boolean add(V key) {
            return backing.add(key);
        }

        @Override
        public boolean remove(final Object o) {
            return backing.remove(o);
        }

        @Override
        public boolean containsAll(@NotNull final Collection<?> c) {
            return backing.containsAll(c);
        }

        @Override
        public boolean addAll(@NotNull final Collection<? extends V> c) {
            return backing.addAll(c);
        }

        @Override
        public boolean removeAll(@NotNull final Collection<?> c) {
            return backing.removeAll(c);
        }

        @Override
        public boolean retainAll(@NotNull final Collection<?> c) {
            return backing.retainAll(c);
        }

        @Override
        public void clear() {
            this.backing.clear();
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }

        @Override
        public boolean contains(final Object o) {
            return backing.contains(o);
        }

        @Override
        public ObjectIterator<V> iterator() {
            return null;
        }

        @Override
        public @NotNull Object[] toArray() {
            return this.backing.toArray();
        }

        @Override
        public @NotNull <T> T[] toArray(@NotNull final T[] a) {
            return this.backing.toArray(a);
        }
    }

    public static class WrappingLongListIterator implements LongListIterator {
        private final ListIterator<Long> backing;

        WrappingLongListIterator(ListIterator<Long> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public long previousLong() {
            return backing.previous();
        }

        @Override
        public long nextLong() {
            return backing.next();
        }

        @Override
        public boolean hasNext() {
            return backing.hasNext();
        }

        @Override
        public boolean hasPrevious() {
            return backing.hasPrevious();
        }

        @Override
        public int nextIndex() {
            return backing.nextIndex();
        }

        @Override
        public int previousIndex() {
            return backing.previousIndex();
        }

        @Override
        public void add(long k) {
            backing.add(k);
        }

        @Override
        public void remove() {
            backing.remove();
        }

        @Override
        public void set(long k) {
            backing.set(k);
        }
    }

    public static class SlimWrappingLongListIterator implements LongListIterator {
        private final Iterator<Long> backing;

        SlimWrappingLongListIterator(Iterator<Long> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public long previousLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long nextLong() {
            return backing.next();
        }

        @Override
        public boolean hasNext() {
            return backing.hasNext();
        }

        @Override
        public boolean hasPrevious() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int nextIndex() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int previousIndex() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(long k) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove() {
            backing.remove();
        }

        @Override
        public void set(long k) {
            throw new UnsupportedOperationException();
        }
    }

    public static class WrappingObjectCollection<V> implements ObjectCollection<V> {
        private final Collection<V> backing;

        public WrappingObjectCollection(Collection<V> backing) {
            this.backing = Objects.requireNonNull(backing);
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return backing.contains(o);
        }

        @Override
        public Object[] toArray() {
            return backing.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return backing.toArray(a);
        }

        @Override
        public boolean add(V e) {
            return backing.add(e);
        }

        @Override
        public boolean remove(Object o) {
            return backing.remove(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return backing.containsAll(c);
        }

        @Override
        public boolean addAll(Collection<? extends V> c) {
            return backing.addAll(c);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            return backing.removeAll(c);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return backing.retainAll(c);
        }

        @Override
        public void clear() {
            backing.clear();
        }

        @Override
        public ObjectIterator<V> iterator() {
            return itrWrap(backing);
        }
    }

    private record WrapperObjectIterator<T>(Iterator<T> parent) implements ObjectIterator<T> {
        private WrapperObjectIterator(Iterator<T> parent) {
            this.parent = Objects.requireNonNull(parent);
        }

        @Override
        public boolean hasNext() {
            return parent.hasNext();
        }

        @Override
        public T next() {
            return parent.next();
        }

        @Override
        public void remove() {
            parent.remove();
        }
    }
}
