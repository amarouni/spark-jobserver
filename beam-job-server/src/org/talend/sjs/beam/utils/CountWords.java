package org.talend.sjs.beam.utils;

import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

/**
 * Created by abbass on 02/06/16.
 */
public class CountWords extends PTransform<PCollection<String>, PCollection<String>> {
    @Override
    public PCollection<String> apply(PCollection<String> lines) {

        // Convert lines of text into individual words.
        PCollection<String> words = lines.apply(
                ParDo.of(new ExtractWordsFn()));

        // Count the number of times each word occurs.
        PCollection<KV<String, Long>> wordCounts =
                words.apply(Count.<String>perElement());

        // Format each word and count into a printable string.

        return wordCounts.apply(ParDo.of(new FormatCountsFn()));
    }

}