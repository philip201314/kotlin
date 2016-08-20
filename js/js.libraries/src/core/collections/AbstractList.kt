/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.collections

public abstract class AbstractList<E> protected constructor() : AbstractCollection<E>(), MutableList<E> {
    abstract override val size: Int
    abstract override fun get(index: Int): E

    override fun set(index: Int, element: E): E = throw UnsupportedOperationException()
    override fun add(index: Int, element: E): Unit = throw UnsupportedOperationException()
    override fun removeAt(index: Int): E = throw UnsupportedOperationException()

    override fun add(element: E): Boolean {
        add(size, element)
        return true
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        var modified = false
        var index = index
        for (element in elements) {
            add(index++, element)
            modified = true
        }
        return modified
    }

    override fun clear() {
        removeRange(0, size)
    }

    protected open fun removeRange(fromIndex: Int, toIndex: Int) {
        val iterator = listIterator(fromIndex)
        repeat(toIndex - fromIndex) {
            iterator.next()
            iterator.remove()
        }
    }

    override fun indexOf(element: E): Int {
        val iterator = listIterator()
        while (iterator.hasNext())
            if (iterator.next() == element)
                return iterator.previousIndex()
        return -1
    }

    override fun lastIndexOf(element: E): Int {
        val iterator = listIterator(size)
        while (iterator.hasPrevious())
            if (iterator.previous() == element)
                return iterator.nextIndex()
        return -1
    }

    override fun iterator(): MutableIterator<E> = ListIterator(0)
    override fun listIterator(): MutableListIterator<E> = ListIterator(0)
    override fun listIterator(index: Int): MutableListIterator<E> {
        if (index !in 0..size) {
            throw IndexOutOfBoundsException("Index: $index, size: $size");
        }
        return ListIterator(index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> = SubList(this, fromIndex, toIndex)


    override fun hashCode(): Int {
        var hashCode = 1
        for (element in this)
            hashCode = 31 * hashCode + (element?.hashCode() ?: 0)
        return hashCode

    }

    override fun equals(other: Any?): Boolean {
        if (other === this)
            return true
        if (other !is List<*>)
            return false

        val iterator1 = listIterator()
        val iterator2 = other.listIterator()
        while (iterator1.hasNext() && iterator2.hasNext()) {
            val element1 = iterator1.next()
            val element2 = iterator2.next()
            if (element1 != element2)
                return false
        }
        return !(iterator1.hasNext() || iterator2.hasNext())
    }

    protected var modCount: Int = 0

    private inner open class ListIterator(private var cursor: Int) : MutableListIterator<E> {
        private var modificationIndex = -1
        private var expectedModCount = modCount

        override fun hasNext() = cursor < size
        override fun hasPrevious() = cursor > 0
        override fun nextIndex(): Int = cursor
        override fun previousIndex(): Int = cursor - 1

        override fun next(): E {
            checkForComodification()
            try {
                return get(cursor).apply { modificationIndex = cursor++ }
            }
            catch (e: IndexOutOfBoundsException) {
                checkForComodification()
                throw NoSuchElementException()
            }
        }

        override fun remove() {
            if (modificationIndex == -1)
                throw IllegalStateException()
            checkForComodification()

            try {
                this@AbstractList.removeAt(modificationIndex)
                if (modificationIndex < cursor)
                    cursor--
                modificationIndex = -1
                expectedModCount = modCount
            }
            catch (e: IndexOutOfBoundsException) {
                throw ConcurrentModificationException()
            }
        }

        private fun checkForComodification() {
            if (modCount != expectedModCount)
                throw ConcurrentModificationException()
        }


        override fun previous(): E {
            checkForComodification()
            try {
                val nextCursor = cursor - 1
                return get(nextCursor).apply {
                    cursor = nextCursor
                    modificationIndex = nextCursor
                }
            }
            catch (e: IndexOutOfBoundsException) {
                checkForComodification()
                throw NoSuchElementException()
            }
        }


        override fun set(e: E) {
            if (modificationIndex == -1)
                throw IllegalStateException()
            checkForComodification()

            try {
                this@AbstractList[modificationIndex] = e
                expectedModCount = modCount
            }
            catch (ex: IndexOutOfBoundsException) {
                throw ConcurrentModificationException()
            }
        }

        override fun add(e: E) {
            checkForComodification()

            try {
                this@AbstractList.add(cursor++, e)
                modificationIndex = -1
                expectedModCount = modCount
            }
            catch (ex: IndexOutOfBoundsException) {
                throw ConcurrentModificationException()
            }
        }
    }

    private class SubList<E>(val list: AbstractList<E>, fromIndex: Int, toIndex: Int) : AbstractList<E>() {
        init {
            if (fromIndex < 0)
                throw IndexOutOfBoundsException("fromIndex = $fromIndex")
            if (toIndex > list.size)
                throw IndexOutOfBoundsException("toIndex = $toIndex")
            if (fromIndex > toIndex)
                throw IllegalArgumentException("fromIndex($fromIndex) > toIndex($toIndex)")
        }
        private val offset = fromIndex
        private var _size = toIndex - fromIndex
        private var expectedModCount = list.modCount

        override val size: Int
            get() = _size.apply { checkForComodification() }

        override fun set(index: Int, element: E): E {
            rangeCheck(index)
            checkForComodification()
            return list.set(index + offset, element)
        }

        override fun get(index: Int): E {
            rangeCheck(index)
            checkForComodification()
            return list.get(index + offset)
        }


        override fun add(index: Int, element: E) {
            insertionRangeCheck(index)
            checkForComodification()
            list.add(index + offset, element)
            expectedModCount = list.modCount
            _size++
            this.modCount++
        }


        override fun removeAt(index: Int): E {
            rangeCheck(index)
            checkForComodification()
            val result = list.removeAt(index + offset)
            expectedModCount = list.modCount
            _size--
            this.modCount++
            return result
        }

        override fun removeRange(fromIndex: Int, toIndex: Int) {
            checkForComodification()
            list.removeRange(fromIndex + offset, toIndex + offset)
            expectedModCount = list.modCount
            _size -= toIndex - fromIndex
            modCount++
        }

        override fun addAll(c: Collection<E>): Boolean = addAll(_size, c)

        override fun addAll(index: Int, elements: Collection<E>): Boolean {
            insertionRangeCheck(index)
            val otherSize = elements.size
            if (otherSize == 0)
                return false

            checkForComodification()
            list.addAll(offset + index, elements)
            expectedModCount = list.modCount
            _size += otherSize
            modCount++
            return true
        }

        override fun iterator(): MutableIterator<E> = listIterator()

        override fun listIterator(index: Int): MutableListIterator<E> {
            checkForComodification()
            insertionRangeCheck(index)

            return object : MutableListIterator<E> {
                private val iterator = list.listIterator(index + offset)

                override operator fun hasNext(): Boolean {
                    return nextIndex() < _size
                }

                override operator fun next(): E {
                    if (hasNext())
                        return iterator.next()
                    else
                        throw NoSuchElementException()
                }

                override fun hasPrevious(): Boolean {
                    return previousIndex() >= 0
                }

                override fun previous(): E {
                    if (hasPrevious())
                        return iterator.previous()
                    else
                        throw NoSuchElementException()
                }

                override fun nextIndex(): Int {
                    return iterator.nextIndex() - offset
                }

                override fun previousIndex(): Int {
                    return iterator.previousIndex() - offset
                }

                override fun remove() {
                    iterator.remove()
                    expectedModCount = list.modCount
                    _size--
                    modCount++
                }

                override fun set(e: E) {
                    iterator.set(e)
                }

                override fun add(e: E) {
                    iterator.add(e)
                    expectedModCount = list.modCount
                    _size++
                    modCount++
                }
            }
        }


        private fun rangeCheck(index: Int) {
            if (index !in 0.._size-1)
                throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        private fun insertionRangeCheck(index: Int) {
            if (index !in 0.._size)
                throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }

        private fun checkForComodification() {
            if (list.modCount != expectedModCount)
                throw ConcurrentModificationException()
        }
    }
}

