/* ABI subset of @kuikly-open/render 2.29.1 include/Kuikly/{Kuikly,KRAnyData}.h.
 * Copyright (C) 2025 Tencent. Licensed under the License of KuiklyUI:
 * https://github.com/Tencent-TDS/KuiklyUI/blob/2.29.1/LICENSE
 * Only C declarations are repeated; no renderer implementation is bundled.
 */
#ifndef GYC_DYNAMIC_KUIKLY_MODULE_H
#define GYC_DYNAMIC_KUIKLY_MODULE_H
#include <stdbool.h>
typedef void *KRAnyData;
struct KRRenderModuleCallbackContextData;
typedef struct KRRenderModuleCallbackContextData *KRRenderModuleCallbackContext;
typedef void *(*KRRenderModuleOnConstruct)(const char *moduleName);
typedef void (*KRRenderModuleOnDestruct)(const void *moduleInstance);
typedef KRAnyData (*KRRenderModuleCallMethodV2)(const void *moduleInstance, const char *moduleName,
    int sync, const char *method, KRAnyData param, KRRenderModuleCallbackContext context);
void KRRenderModuleRegisterV2(const char *moduleName, KRRenderModuleOnConstruct onConstruct,
    KRRenderModuleOnDestruct onDestruct, KRRenderModuleCallMethodV2 onCallMethod, void *reserved);
void KRRenderModuleDoCallback(KRRenderModuleCallbackContext context, const char *data);
bool KRAnyDataIsString(KRAnyData data);
const char *KRAnyDataGetString(KRAnyData data);
KRAnyData KRAnyDataCreate(void);
#endif
