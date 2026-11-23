# JNA finds the classes it marshals through reflection, which minification strips or renames: without these rules a minified app loads the
# library and marshals wrong
-keep class com.sun.jna.** { *; }
# the interface of the library and the callbacks extend a type of JNA: minification strips the methods of an interface that it sees no
# implementation of, and rewrites the call sites of the app to throw, and the callback that the native library calls loses the method it
# calls by its name
-keep interface * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
# a structure that JNA maps reads its fields by their names, which minification renames
-keepclassmembers class * extends com.sun.jna.** { <fields>; }
# the models cross the native ABI as JSON through their fields, and a field that minification renames is a property the ABI cannot read:
# the names of the fields are the names of the JSON
-keepclassmembers class monero.** { <fields>; <init>(); }
# jackson fills and reads the properties of the models through their getters and setters, which minification renames: the property that
# loses its accessor deserializes to null in silence, which no error says
-keepclassmembers class monero.** {
    public *** get*();
    public void set*(...);
}
# jackson calls the methods that carry its annotations, which minification removes with the methods
-keepclassmembers class monero.** {
    @com.fasterxml.jackson.annotation.JsonValue <methods>;
    @com.fasterxml.jackson.annotation.JsonCreator <methods>;
}
# the enumerations cross the abi as their index, which jackson reads through values(), and its own enumerations through their constants:
# the ones of the library alone unbox, which turns the class into one that getEnumConstants() answers null for
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# the reified reads of the models carry their types in the signatures of their references
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
# minification rewrites the signature of a class that extends a renamed TypeReference to the erased form, which the constructor of
# TypeReference reads as no type information at all: the class and its subclasses keep their names, which keeps the signatures whole
-keep class com.fasterxml.jackson.core.type.TypeReference { *; }
-keep class * extends com.fasterxml.jackson.core.type.TypeReference { *; }
# jna references the classes of java.awt, which an android app does not carry
-dontwarn java.awt.**
