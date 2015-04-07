package edu.stanford.nlp.mt.train;

import java.io.IOException;
import java.io.LineNumberReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.stanford.nlp.mt.tm.DynamicTranslationModel;
import edu.stanford.nlp.mt.train.AlignmentSymmetrizer.SymmetrizationType;
import edu.stanford.nlp.mt.util.IOTools;
import edu.stanford.nlp.mt.util.IString;
import edu.stanford.nlp.mt.util.ParallelCorpus;
import edu.stanford.nlp.mt.util.ParallelSuffixArray;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;

/**
 * Builds a dynamic translation model based on suffix arrays.
 * 
 * @author Spence Green
 *
 */
public class DynamicTMBuilder {

  private static final Logger logger = LogManager.getLogger(DynamicTMBuilder.class);

  private ParallelSuffixArray sa;
  
  public DynamicTMBuilder(ParallelCorpus corpus) {
    sa = new ParallelSuffixArray(corpus);
  }
  
  public DynamicTMBuilder(String sourceFile, String targetFile, String align, int expectedSize, 
      boolean isDecoderLocal) {
    sa = new ParallelSuffixArray(sourceFile, targetFile, align, expectedSize, isDecoderLocal);
  }
  
  public DynamicTMBuilder(String sourceFile, String targetFile, String feAlign, String efAlign, 
      int expectedSize, SymmetrizationType type, boolean isDecoderLocal) {
    sa = symmetrizeAndCreateSuffixArray(sourceFile, targetFile, feAlign, efAlign, type, expectedSize, isDecoderLocal);
  }
  
  public DynamicTranslationModel<IString,String> getModel() {
    return new DynamicTranslationModel<>(sa);
  }
  
  /**
   * Symmetrize the alignments and create a suffix array.
   * 
   * @param sourceFile
   * @param targetFile
   * @param feAlign
   * @param efAlign
   * @param initialCapacity
   * @param isDecoderLocal 
   * @return
   */
  private ParallelSuffixArray symmetrizeAndCreateSuffixArray(String sourceFile,
      String targetFile, String feAlign, String efAlign, SymmetrizationType type, 
      int initialCapacity, boolean isDecoderLocal) {
    ParallelCorpus corpus = new ParallelCorpus(initialCapacity, isDecoderLocal);
    
    LineNumberReader fReader = IOTools.getReaderFromFile(sourceFile);
    LineNumberReader eReader = IOTools.getReaderFromFile(targetFile);
    LineNumberReader feReader = IOTools.getReaderFromFile(feAlign);
    LineNumberReader efReader = IOTools.getReaderFromFile(efAlign);
    
    try {
      for (String fLine; (fLine = fReader.readLine()) != null; ) {
        String eLine = eReader.readLine();
        String ef1 = efReader.readLine();
        String ef2 = efReader.readLine();
        String ef3 = efReader.readLine();
        String fe1 = feReader.readLine();
        String fe2 = feReader.readLine();
        String fe3 = feReader.readLine();

        GIZAWordAlignment gizaAlign = new GIZAWordAlignment(fe1, fe2,
            fe3, ef1, ef2, ef3);
        SymmetricalWordAlignment symAlign = AlignmentSymmetrizer
            .symmetrize(gizaAlign, type);
        symAlign.reverse();
        String aLine = symAlign.toString().trim();
        corpus.add(fLine, eLine, aLine);
      }

      fReader.close();
      eReader.close();
      feReader.close();
      efReader.close();
      
    } catch (IOException e) {
      logger.error("Error at line {}", fReader.getLineNumber());
      logger.error("Exception: ", e);
    }
    
    return new ParallelSuffixArray(corpus);
  }

  private static Map<String, Integer> optionDefs() {
    Map<String,Integer> optionDefs = new HashMap<>();
    optionDefs.put("o", 1);
    optionDefs.put("e", 1);
    optionDefs.put("s", 1);
    optionDefs.put("d", 0);
    return optionDefs;
  }  

  private static String usage() {
    StringBuilder sb = new StringBuilder();
    String nl = System.getProperty("line.separator");
    sb.append("Usage: java ").append(DynamicTMBuilder.class.getName()).append(" OPTS src target alignf2e [aligne2f]").append(nl);
    sb.append(nl).append(" Options:").append(nl)
    .append("   -o file-name   : Output file name.").append(nl)
    .append("   -e num         : Estimated size of corpus (num. lines)").append(nl)
    .append("   -s type        : Symmetrization type.").append(nl)
    .append("   -d             : Decoder-local translation model.").append(nl);
    return sb.toString();
  }
  
  /**
   * 
   * @param args
   */
  public static void main(String[] args) {
    if (args.length < 1 || args[0].equals("-h") || args[0].equals("-help")) {
      System.err.print(usage());
      System.exit(-1);
    }
    Properties options = StringUtils.argsToProperties(args, optionDefs());
    String[] positionalArgs = options.getProperty("").split("\\s+");
    if (positionalArgs.length < 3) {
      System.err.print(usage());
      System.exit(-1);      
    }
    
    String outputFileName = options.getProperty("o", "tm.ser.gz");
    SymmetrizationType type = options.containsKey("s") ? SymmetrizationType.valueOf(options.getProperty("s"))
        : null;
    int initialCapacity = PropertiesUtils.getInt(options, "e", 10000);
    boolean isDecoderLocal = PropertiesUtils.getBool(options, "d", false);
    
    String sourceFile = positionalArgs[0];
    String targetFile = positionalArgs[1];
    String alignFEfile = positionalArgs[2];
    String alignEFfile = positionalArgs.length == 4 ? positionalArgs[3] : null;
    
    logger.info("Source file: {}", sourceFile);
    logger.info("Target file: {}", targetFile);
    logger.info("Alignment file (f2e): {}", alignFEfile);
    if (alignEFfile != null) logger.info("Alignment file (e2f): {}", alignEFfile);
    DynamicTMBuilder tmBuilder = alignEFfile == null ? new DynamicTMBuilder(sourceFile, targetFile, alignFEfile, initialCapacity, isDecoderLocal) :
      new DynamicTMBuilder(sourceFile, targetFile, alignFEfile, alignEFfile, initialCapacity, type, isDecoderLocal);
    DynamicTranslationModel<IString,String> tm = tmBuilder.getModel();
    
    // TODO(spenceg) Replace with kryo
    try {
      logger.info("Serializing to: " + outputFileName);
      IOTools.serialize(outputFileName, tm);
    } catch (IOException e) {
      logger.error("Unable to serialize to: " + outputFileName, e);
    }
  }
}
