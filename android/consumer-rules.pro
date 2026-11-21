# JNA finds the classes it marshals through reflection, which minification strips or renames:
# without these rules a minified app loads the library and marshals wrong
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# the whole surface of the library is reflective: the models that the json of monero-c fills by
# their property names, the MoneroC interface whose method names are the symbols of the abi, and
# the connection that crosses as json. a minified app that drops any of them breaks at the first
# answer of the wallet, which the runtime smoke of a minified build catches
-keep class monero.** { *; }

# jackson reads the types of the answers from the generic signatures, and the enums of the models
# by their constants, which minification erases and unboxes without these: a signature that lost
# its type answers ClassCastException at the use of what it made, and an unboxed enum answers that
# TypeReference was constructed without type information
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keep class com.fasterxml.jackson.core.type.TypeReference { *; }
