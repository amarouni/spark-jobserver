package org.talend.sjs.beam.utils;

/**
 * Created by abbass on 02/06/16.
 */

import org.apache.beam.sdk.transforms.OldDoFn;
import org.apache.beam.sdk.values.KV;

public class FormatCountsFn extends OldDoFn<KV<String, Long>, String> {
    @Override
    public void processElement(ProcessContext c) {
        c.output(c.element().getKey() + ": " + c.element().getValue());
    }
}
