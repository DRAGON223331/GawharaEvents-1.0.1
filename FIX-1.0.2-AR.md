# إصلاح GawharaEvents 1.0.2

## سبب فشل البناء
كان الكود يستخدم `Particle.EXPLOSION_LARGE`، وهو اسم غير موجود في Paper 26.3.

## الإصلاح
تم استبداله بـ `Particle.EXPLOSION`، وهو ثابت موجود في Paper 26.3 API.

## البناء
يتطلب Java 25 وMaven. شغّل:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

الملف الناتج:

```text
target/GawharaEvents-1.0.2.jar
```

تحذير `deprecated API` الذي ظهر في البناء السابق لا يوقف التجميع. سبب الفشل كان فقط الرمز غير الموجود `EXPLOSION_LARGE`.
