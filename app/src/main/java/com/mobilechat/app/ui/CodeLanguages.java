package com.mobilechat.app.ui;

import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(includeAll = true, grammarLocatorClassName = ".CodeGrammarLocator")
public final class CodeLanguages {
    private CodeLanguages() {}

    public static Prism4j create() {
        return new Prism4j(new CodeGrammarLocator());
    }
}
