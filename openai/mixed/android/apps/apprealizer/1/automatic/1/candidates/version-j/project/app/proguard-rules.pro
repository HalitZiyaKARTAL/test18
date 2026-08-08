-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep class a.htmlapprealizer.KotlinProbe { *; }
-keep class a.htmlapprealizer.KotlinProbe$Companion { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-if class kotlin.reflect.full.KClasses
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
