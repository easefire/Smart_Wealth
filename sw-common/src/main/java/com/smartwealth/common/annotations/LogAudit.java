package com.smartwealth.common.annotations;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogAudit {
    /** 模块名称 */
    String module() default "";

    /** 操作描述 */
    String operation() default "";
}
