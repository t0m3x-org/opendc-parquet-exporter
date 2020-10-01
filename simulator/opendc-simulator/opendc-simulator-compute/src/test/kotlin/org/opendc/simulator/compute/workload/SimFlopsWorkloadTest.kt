/*
 * Copyright (c) 2020 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.simulator.compute.workload

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Test suite for [SimFlopsWorkload] class.
 */
class SimFlopsWorkloadTest {
    @Test
    fun testFlopsNonNegative() {
        assertThrows<IllegalArgumentException>("FLOPs must be non-negative") {
            SimFlopsWorkload(-1, 1)
        }
    }

    @Test
    fun testCoresNonZero() {
        assertThrows<IllegalArgumentException>("Cores cannot be zero") {
            SimFlopsWorkload(1, 0)
        }
    }

    @Test
    fun testCoresPositive() {
        assertThrows<IllegalArgumentException>("Cores cannot be negative") {
            SimFlopsWorkload(1, -1)
        }
    }

    @Test
    fun testUtilizationNonZero() {
        assertThrows<IllegalArgumentException>("Utilization cannot be zero") {
            SimFlopsWorkload(1, 1, 0.0)
        }
    }

    @Test
    fun testUtilizationPositive() {
        assertThrows<IllegalArgumentException>("Utilization cannot be negative") {
            SimFlopsWorkload(1, 1, -1.0)
        }
    }

    @Test
    fun testUtilizationNotLargerThanOne() {
        assertThrows<IllegalArgumentException>("Utilization cannot be larger than one") {
            SimFlopsWorkload(1, 1, 2.0)
        }
    }
}
