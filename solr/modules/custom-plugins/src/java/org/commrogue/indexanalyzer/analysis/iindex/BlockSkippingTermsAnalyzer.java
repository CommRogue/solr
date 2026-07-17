package org.commrogue.indexanalyzer.analysis.iindex;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.lucene103.blocktree.Lucene103BlockTreeTermsReader;
import org.apache.lucene.index.*;
import org.apache.lucene.store.IndexInput;
import org.commrogue.indexanalyzer.LuceneFileExtension;
import org.commrogue.indexanalyzer.lucene.Utils;

public class BlockSkippingTermsAnalyzer {
    private static final String TERMS_META_CODEC_NAME = "BlockTreeTermsMeta";
    private static final String TERMS_CODEC_NAME = "Lucene103PostingsWriterTerms";

    // Lucene103PostingsFormat.VERSION_START / VERSION_CURRENT are package-private; both are 0.
    private static final int POSTINGS_VERSION_START = 0;
    private static final int POSTINGS_VERSION_CURRENT = 0;

    /**
     * Note - unlike the Lucene90 BlockTree format, the Lucene103 terms metadata no longer records
     * any file pointer into the .tim dictionary (the FST index was replaced by a trie whose root
     * offset points inside the field's .tip slice), so per-field dictionary (.tim) attribution is
     * not available in block-skipping mode and {@link TermsAnalysis#dictionarySize()} is always 0.
     * Use the instrumented mode for accurate per-field .tim sizes. The index (.tip) size, on the
     * other hand, is now exact per field rather than a delta-based approximation.
     */
    public record TermsAnalysis(long metadataSize, long indexSize, long dictionarySize) {}

    private record TermsFPs(int fieldNum, long metadataFP, long indexStartFP, long indexEndFP) {}

    private static TermsFPs[] readTermFPs(SegmentReadState state) throws IOException {
        String metaName = IndexFileNames.segmentFileName(
                state.segmentInfo.name, state.segmentSuffix, LuceneFileExtension.TMD.getExtension());

        try (IndexInput metaIn = state.directory.openInput(metaName, state.context)) {
            // Header -> IndexHeader
            CodecUtil.checkIndexHeader(
                    metaIn,
                    TERMS_META_CODEC_NAME,
                    Lucene103BlockTreeTermsReader.VERSION_START,
                    Lucene103BlockTreeTermsReader.VERSION_CURRENT,
                    state.segmentInfo.getId(),
                    state.segmentSuffix);

            // Written by Lucene103PostingsWriter and consumed by Lucene103PostingsReader.init():
            // a postings header followed by the index block size.
            CodecUtil.checkIndexHeader(
                    metaIn,
                    TERMS_CODEC_NAME,
                    POSTINGS_VERSION_START,
                    POSTINGS_VERSION_CURRENT,
                    state.segmentInfo.getId(),
                    state.segmentSuffix);

            // IndexBlockSize -> VInt
            metaIn.readVInt();

            // NumFields -> VInt
            final int numFields = metaIn.readVInt();

            if (numFields < 0) {
                throw new CorruptIndexException("invalid numFields: " + numFields, metaIn);
            }

            TermsFPs[] termsFPs = new TermsFPs[numFields];

            for (int fieldCounter = 0; fieldCounter < numFields; fieldCounter++) {
                // FieldNumber -> VInt
                final int fieldNum = metaIn.readVInt();
                final FieldInfo fieldInfo = state.fieldInfos.fieldInfo(fieldNum);
                if (fieldInfo == null) {
                    throw new CorruptIndexException("invalid field number: " + fieldNum, metaIn);
                }

                // NumTerms -> VLong
                metaIn.readVLong();

                // SumTotalTermFreq -> VLong
                metaIn.readVLong();

                // SumDocFreq? -> VLong
                // when frequencies are omitted, sumDocFreq = sumTotalTermFreq and is not written
                if (fieldInfo.getIndexOptions() != IndexOptions.DOCS) metaIn.readVLong();

                // DocCount -> VInt
                metaIn.readVInt();

                // MinTerm -> VInt length followed by byte[]
                Utils.readBytesRef(metaIn);

                // MaxTerm -> VInt length followed by byte[]
                Utils.readBytesRef(metaIn);

                // IndexStartFP -> VLong (offset of this field's trie index within .tip)
                final long indexStartFP = metaIn.readVLong();

                // RootFP -> VLong (offset of the trie root node inside this field's .tip slice;
                // carries no .tim position, see the TermsAnalysis note)
                metaIn.readVLong();

                // IndexEndFP -> VLong
                final long indexEndFP = metaIn.readVLong();

                termsFPs[fieldCounter] = new TermsFPs(fieldNum, metaIn.getFilePointer(), indexStartFP, indexEndFP);
            }

            return termsFPs;
        }
    }

    /**
     * Attempts to analyze terms-related files (.tip, .tim, and .tmd) by reading the .tmd (separate file from .tip since 8.6 - see LUCENE-9353),
     * which includes file offsets to the relevant files for each field.
     *
     * @param state {@link SegmentReadState} for the relevant segment.
     * @return array of {@link TermsAnalysis}. This array is always of size numFields, and is sorted by field numbers.
     */
    public static Map<String, TermsAnalysis> analyze(SegmentReadState state) throws IOException {
        TermsFPs[] termsFPs = readTermFPs(state);

        HashMap<String, TermsAnalysis> analysisResult = new HashMap<>();

        analysisResult.put(
                state.fieldInfos.fieldInfo(termsFPs[0].fieldNum).getName(),
                new TermsAnalysis(
                        termsFPs[0].metadataFP, termsFPs[0].indexEndFP - termsFPs[0].indexStartFP, 0));

        for (int i = 1; i < termsFPs.length; i++) {
            analysisResult.put(
                    state.fieldInfos.fieldInfo(termsFPs[i].fieldNum).getName(),
                    new TermsAnalysis(
                            termsFPs[i].metadataFP - termsFPs[i - 1].metadataFP,
                            termsFPs[i].indexEndFP - termsFPs[i].indexStartFP,
                            0));
        }

        return analysisResult;
    }
}
