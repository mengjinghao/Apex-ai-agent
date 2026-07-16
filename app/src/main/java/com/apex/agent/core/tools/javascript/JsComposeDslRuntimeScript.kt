package com.apex.core.tools.javascript

internal fun buildComposeDslRuntimeWrappedScript(script: String): String {
    return """
        ${script}

        (function() {
            function __Apex_is_promise(__value) {
                return !!(__value && typeof __value.then === 'function');
            }

            function __Apex_wrap_compose_response(__bundle, __tree) {
                return {
                    tree: __tree,
                    state: __bundle.state,
                    memo: __bundle.memo
                };
            }

            function __Apex_build_compose_response(__bundle, __entry) {
                var __tree = __entry(__bundle.ctx);
                if (__Apex_is_promise(__tree)) {
                    return __tree.then(function(__resolvedTree) {
                        return __Apex_wrap_compose_response(__bundle, __resolvedTree);
                    });
                }
                return __Apex_wrap_compose_response(__bundle, __tree);
            }

            function __ApexResolveComposeEntry() {
                try {
                    if (typeof module !== 'undefined' && module && module.exports) {
                        if (typeof module.exports.default === 'function') {
                            return module.exports.default;
                        }
                        if (typeof module.exports.Screen === 'function') {
                            return module.exports.Screen;
                        }
                    }
                    if (typeof exports !== 'undefined' && exports) {
                        if (typeof exports.default === 'function') {
                            return exports.default;
                        }
                        if (typeof exports.Screen === 'function') {
                            return exports.Screen;
                        }
                    }
                    if (typeof window !== 'undefined') {
                        if (typeof window.default === 'function') {
                            return window.default;
                        }
                        if (typeof window.Screen === 'function') {
                            return window.Screen;
                        }
                    }
                } catch (e) {
                    console.error('resolve compose entry failed:', e);
                }
                return null;
            }

            function __Apex_render_compose_dsl(__runtimeOptions) {
                if (typeof ApexComposeDslRuntime === 'undefined') {
                    throw new Error('ApexComposeDslRuntime bridge is not initialized');
                }
                var __root = typeof globalThis !== 'undefined'
                    ? globalThis
                    : (typeof window !== 'undefined' ? window : this);
                var __activeCallRuntime =
                    typeof __root.__Apex_call_runtime_ref === 'object' && __root.__Apex_call_runtime_ref
                        ? __root.__Apex_call_runtime_ref
                        : null;
                var __options = __runtimeOptions && typeof __runtimeOptions === 'object'
                    ? Object.assign({}, __runtimeOptions)
                    : {};
                if (__activeCallRuntime) {
                    __options.__Apex_call_runtime = __activeCallRuntime;
                }
                var __bundle = ApexComposeDslRuntime.createContext(__options);
                var __entry = __ApexResolveComposeEntry();
                if (typeof __entry !== 'function') {
                    throw new Error(
                        'compose_dsl entry function not found, expected default export or Screen function'
                    );
                }
                if (__activeCallRuntime && typeof __bundle.setCallRuntime === 'function') {
                    __bundle.setCallRuntime(__activeCallRuntime);
                }
                __root.__Apex_compose_bundle = __bundle;
                __root.__Apex_compose_entry = __entry;
                return __Apex_build_compose_response(__bundle, __entry);
            }

            function __Apex_dispatch_compose_dsl_action(__actionRequest) {
                var __root = typeof globalThis !== 'undefined'
                    ? globalThis
                    : (typeof window !== 'undefined' ? window : this);
                var __bundle = __root.__Apex_compose_bundle;
                var __entry = __root.__Apex_compose_entry;
                if (!__bundle || typeof __entry !== 'function') {
                    throw new Error('compose_dsl runtime is not initialized, render first');
                }
                if (typeof __bundle.invokeAction !== 'function') {
                    throw new Error('compose_dsl runtime action bridge is not available');
                }
                var __activeCallRuntime =
                    typeof __root.__Apex_call_runtime_ref === 'object' && __root.__Apex_call_runtime_ref
                        ? __root.__Apex_call_runtime_ref
                        : null;
                if (__activeCallRuntime && typeof __bundle.setCallRuntime === 'function') {
                    __bundle.setCallRuntime(__activeCallRuntime);
                }

                var __request =
                    __actionRequest && typeof __actionRequest === 'object'
                        ? __actionRequest
                        : {};
                var __actionId = String(
                    __request.__action_id || __request.actionId || ''
                ).trim();
                if (!__actionId) {
                    throw new Error('compose action id is required');
                }

                var __payload =
                    Object.prototype.hasOwnProperty.call(__request, '__action_payload')
                        ? __request.__action_payload
                        : __request.payload;
                var __noRender =
                    __payload &&
                    typeof __payload === 'object' &&
                    (__payload.__no_render === true ||
                        __payload.__noRender === true ||
                        __payload.__local === true);

                function __Apex_send_intermediate_result(__value) {
                    if (typeof sendIntermediateResult !== 'function') {
                        return;
                    }
                    sendIntermediateResult(__value);
                }

                var __actionSettled = false;
                var __intermediateRenderQueued = false;
                var __intermediateRenderInFlight = false;
                var __unsubscribeStateChange = null;

                function __Apex_finalize_action() {
                    __actionSettled = true;
                    if (typeof __unsubscribeStateChange === 'function') {
                        try {
                            __unsubscribeStateChange();
                        } catch (__unsubscribeError) {
                        }
                        __unsubscribeStateChange = null;
                    }
                }

                function __Apex_render_and_send_intermediate() {
                    if (__actionSettled) {
                        return null;
                    }
                    try {
                        var __intermediateResponse = __Apex_build_compose_response(__bundle, __entry);
                        if (__Apex_is_promise(__intermediateResponse)) {
                            return __intermediateResponse.then(function(__resolvedIntermediate) {
                                if (!__actionSettled) {
                                    __Apex_send_intermediate_result(__resolvedIntermediate);
                                }
                            });
                        }
                        __Apex_send_intermediate_result(__intermediateResponse);
                    } catch (__intermediateError) {
                        try {
                            console.warn('compose intermediate render failed:', __intermediateError);
                        } catch (__ignore) {
                        }
                    }
                    return null;
                }

                function __Apex_process_intermediate_queue() {
                    if (__actionSettled || __intermediateRenderInFlight || !__intermediateRenderQueued) {
                        return;
                    }
                    __intermediateRenderQueued = false;
                    __intermediateRenderInFlight = true;
                    var __renderResult = __Apex_render_and_send_intermediate();
                    if (__Apex_is_promise(__renderResult)) {
                        __renderResult.then(
                            function() {},
                            function() {}
                        ).then(function() {
                            __intermediateRenderInFlight = false;
                            if (__intermediateRenderQueued && !__actionSettled) {
                                __Apex_process_intermediate_queue();
                            }
                        });
                        return;
                    }
                    __intermediateRenderInFlight = false;
                    if (__intermediateRenderQueued && !__actionSettled) {
                        __Apex_process_intermediate_queue();
                    }
                }

                function __Apex_schedule_intermediate_render() {
                    if (__actionSettled) {
                        return;
                    }
                    __intermediateRenderQueued = true;
                    Promise.resolve().then(function() {
                        __Apex_process_intermediate_queue();
                    });
                }

                if (typeof __bundle.subscribeStateChange === 'function') {
                    if (!__noRender) {
                        __unsubscribeStateChange = __bundle.subscribeStateChange(function() {
                            __Apex_schedule_intermediate_render();
                        });
                    }
                }

                var __maybePromise;
                try {
                    __maybePromise = __bundle.invokeAction(__actionId, __payload);
                } catch (__actionError) {
                    __Apex_finalize_action();
                    throw __actionError;
                }
                if (__maybePromise && typeof __maybePromise.then === 'function') {
                    if (!__noRender) {
                        // For async actions, schedule a render checkpoint immediately.
                        // Additional state updates during await phases are pushed by state-change listeners.
                        __Apex_schedule_intermediate_render();
                    }
                    return __maybePromise.then(function() {
                        __Apex_finalize_action();
                        if (__noRender) {
                            return null;
                        }
                        return __Apex_build_compose_response(__bundle, __entry);
                    }, function(__actionError) {
                        __Apex_finalize_action();
                        throw __actionError;
                    });
                }
                __Apex_finalize_action();
                if (__noRender) {
                    return null;
                }
                return __Apex_build_compose_response(__bundle, __entry);
            }

            if (typeof exports !== 'undefined' && exports) {
                exports.__Apex_render_compose_dsl = __Apex_render_compose_dsl;
                exports.__Apex_dispatch_compose_dsl_action =
                    __Apex_dispatch_compose_dsl_action;
            }
            if (typeof module !== 'undefined' && module && module.exports) {
                module.exports.__Apex_render_compose_dsl = __Apex_render_compose_dsl;
                module.exports.__Apex_dispatch_compose_dsl_action =
                    __Apex_dispatch_compose_dsl_action;
            }
            var __root = typeof globalThis !== 'undefined'
                ? globalThis
                : (typeof window !== 'undefined' ? window : this);
            __root.__Apex_render_compose_dsl = __Apex_render_compose_dsl;
            __root.__Apex_dispatch_compose_dsl_action =
                __Apex_dispatch_compose_dsl_action;
        })();
    """.trimIndent()
}
