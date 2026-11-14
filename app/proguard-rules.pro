# La versión release no se minifica (isMinifyEnabled = false). Si se activa,
# BouncyCastle necesita estas reglas:
-dontwarn javax.naming.**
-keep class org.bouncycastle.** { *; }
