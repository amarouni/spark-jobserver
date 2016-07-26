package org.talend.sjs.beam.utils;

import org.apache.beam.sdk.transforms.Aggregator;
import org.apache.beam.sdk.transforms.OldDoFn;
import org.apache.beam.sdk.transforms.Sum;

import java.util.regex.Pattern;

/**
 * Created by abbass on 02/06/16.
 */
public class ExtractWordsFn extends OldDoFn<String, String> {
    private static final Pattern WORD_BOUNDARY = Pattern.compile("[^a-zA-Z']+");
    private final Aggregator<Long, Long> emptyLines =
            createAggregator("emptyLines", new Sum.SumLongFn());

    @Override
    public void processElement(ProcessContext c) {
        // Split the line into words.
        String[] words = WORD_BOUNDARY.split(c.element());

        // Keep track of the number of lines without any words encountered while tokenizing.
        // This aggregator is visible in the monitoring UI when run using DataflowPipelineRunner.
        if (words.length == 0) {
            emptyLines.addValue(1L);
        }

        // Output each word encountered into the output PCollection.
        for (String word : words) {
            if (!word.isEmpty()) {
                c.output(word);
            }
        }
    }
}
