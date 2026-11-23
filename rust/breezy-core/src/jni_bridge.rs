//! JNI entry points for `dev.breezy.browser.adblock.NativeShield` (a Kotlin `object` whose
//! methods are `@JvmStatic external`).
//!
//! Large inputs (filter lists, resources) cross the boundary as UTF-8 `ByteArray`s to skip the
//! modified-UTF-8 conversion JNI applies to `String`s.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};

use crate::Decision;

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn new_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn bool_j(b: bool) -> jboolean {
    if b { JNI_TRUE } else { JNI_FALSE }
}

/// `compileLists(lists: Array<ByteArray>): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_compileLists(
    mut env: JNIEnv,
    _class: JClass,
    lists: JObjectArray,
) -> jboolean {
    let Ok(len) = env.get_array_length(&lists) else {
        return JNI_FALSE;
    };
    let mut texts = Vec::with_capacity(len as usize);
    for i in 0..len {
        let Ok(obj) = env.get_object_array_element(&lists, i) else {
            return JNI_FALSE;
        };
        let bytes = JByteArray::from(obj);
        let Ok(raw) = env.convert_byte_array(&bytes) else {
            return JNI_FALSE;
        };
        let _ = env.delete_local_ref(bytes);
        texts.push(String::from_utf8_lossy(&raw).into_owned());
    }
    crate::load_lists(texts.iter().map(String::as_str));
    JNI_TRUE
}

/// `loadSerialized(bytes: ByteArray): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_loadSerialized(
    env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jboolean {
    match env.convert_byte_array(&bytes) {
        Ok(raw) => bool_j(crate::load_serialized(&raw)),
        Err(_) => JNI_FALSE,
    }
}

/// `loadSerializedFile(path: String): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_loadSerializedFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jboolean {
    match jstr(&mut env, &path) {
        Some(p) => bool_j(crate::load_serialized_file(&p)),
        None => JNI_FALSE,
    }
}

/// `serialize(): ByteArray`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_serialize(
    env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    let data = crate::serialize_current();
    env.byte_array_from_slice(&data)
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// `loadResources(json: ByteArray): Int` — number of resources, or -1 on error.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_loadResources(
    env: JNIEnv,
    _class: JClass,
    json: JByteArray,
) -> jint {
    let Ok(raw) = env.convert_byte_array(&json) else {
        return -1;
    };
    match std::str::from_utf8(&raw).map_err(|e| e.to_string()).and_then(crate::load_resources) {
        Ok(n) => n as jint,
        Err(_) => -1,
    }
}

/// `isLoaded(): Boolean`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_isLoaded(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    bool_j(crate::is_loaded())
}

/// `check(url: String, sourceUrl: String?, accept: String?, isMainFrame: Boolean, method: String?): String?`
///
/// Returns `null` to allow, `""` to block, or a `data:` URL to serve instead.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_check(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source_url: JString,
    accept: JString,
    is_main_frame: jboolean,
    method: JString,
) -> jstring {
    let Some(url) = jstr(&mut env, &url) else {
        return std::ptr::null_mut();
    };
    let source = jstr(&mut env, &source_url).unwrap_or_default();
    let accept = jstr(&mut env, &accept).unwrap_or_default();
    let method = jstr(&mut env, &method).unwrap_or_else(|| "GET".into());
    let req_type = crate::request_type::infer(&url, &accept, is_main_frame == JNI_TRUE);
    match crate::check(&url, &source, req_type, &method) {
        Decision::Allow => std::ptr::null_mut(),
        Decision::Block => new_jstring(&mut env, ""),
        Decision::Redirect(data_url) => new_jstring(&mut env, &data_url),
    }
}

/// `cosmeticResources(url: String): String` (JSON)
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_cosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let url = jstr(&mut env, &url).unwrap_or_default();
    let json = crate::cosmetic_resources_json(&url);
    new_jstring(&mut env, &json)
}

/// `hiddenSelectors(classesJson: String, idsJson: String, exceptionsJson: String): String` (JSON array)
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_breezy_browser_adblock_NativeShield_hiddenSelectors(
    mut env: JNIEnv,
    _class: JClass,
    classes: JString,
    ids: JString,
    exceptions: JString,
) -> jstring {
    let classes = jstr(&mut env, &classes).unwrap_or_else(|| "[]".into());
    let ids = jstr(&mut env, &ids).unwrap_or_else(|| "[]".into());
    let exceptions = jstr(&mut env, &exceptions).unwrap_or_else(|| "[]".into());
    let json = crate::hidden_class_id_selectors_json(&classes, &ids, &exceptions);
    new_jstring(&mut env, &json)
}
