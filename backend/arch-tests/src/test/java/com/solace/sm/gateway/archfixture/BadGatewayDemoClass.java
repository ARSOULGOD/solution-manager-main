package com.solace.sm.gateway.archfixture;

import com.solace.sm.demo.archfixture.DemoCompositionType;

/** Planted violation: a production class depending on the demo module (rule {@code nothingProductionImportsTheDemoModule}). */
public final class BadGatewayDemoClass {

    private DemoCompositionType demo;

    public DemoCompositionType demo() {
        return demo;
    }
}
