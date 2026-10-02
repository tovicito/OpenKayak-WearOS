## 2025-02-21 - Avoid Location.distanceBetween in frequent track analysis loops
**Learning:** `android.location.Location.distanceBetween` allocates a `FloatArray(1)` on every call and incurs JNI/framework overhead. When called thousands of times inside nested GPS track clustering, resampling, and sequence analysis routines, this causes heavy GC pressure and CPU cycles on mobile and Wear OS devices.
**Action:** Use an equirectangular distance approximation for short distance local comparisons (< 10km) in offline algorithms to eliminate framework allocation and JNI overhead.
