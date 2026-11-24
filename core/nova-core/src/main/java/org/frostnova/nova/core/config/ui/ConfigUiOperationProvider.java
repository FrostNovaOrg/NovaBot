package org.frostnova.nova.core.config.ui;

import java.util.List;

/** 插件的控制台审计动作。登记的路由只记录动作及结果。 */
public interface ConfigUiOperationProvider {
    List<Operation> operations();

    /** route 为 /config/api/ 之后的路径，可包含 MVC 路径变量。 */
    record Operation(String method, String route, String name, String description) {}
}
