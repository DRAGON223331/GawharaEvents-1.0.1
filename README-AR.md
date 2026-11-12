# GawharaEvents 1.1.0 — GawharaCoins Integration

حافظنا على مكافآت Vault النقدية القديمة، وأضفنا مكافآت GC إضافية للفائز:
- KOTH: 1 GC
- Treasure Hunt: 2 GC
- Meteor Drop: 3 GC

يمكن تغييرها من `coin-rewards` في config.yml. لكل فعالية UUID ومعرّف مكافأة idempotent حتى لا تتكرر المكافأة. يحتاج Vault كما كان سابقًا، ويستخدم GawharaCoins عبر `softdepend`.

الأوامر: `/gevents`, `/gevents start <koth|treasure|meteor>`, `/gevents stop`, `/gevents setlocation <type>`, `/gevents reload`.

البناء: Java 25 + Paper 26.3 build 170-beta. ثبّت GawharaCoins قبل GawharaEvents.
