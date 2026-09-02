/* jni_md.h — définitions machine-dépendantes JNI pour Windows x86-64 (win32/).
 * Fourni pour la CROSS-COMPILATION du backend spine JNI (libhostspine64.dll) avec MinGW sur une
 * machine Linux : le JDK Linux n'a que include/linux/jni_md.h. Contenu = celui du JDK OpenJDK
 * win32/jni_md.h (types + macros d'export). Sur x86-64, __stdcall n'entraîne aucune décoration de
 * nom (une seule convention d'appel) → les symboles Java_* restent des noms C nus, résolus par la JVM.
 */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif /* !_JAVASOFT_JNI_MD_H_ */
