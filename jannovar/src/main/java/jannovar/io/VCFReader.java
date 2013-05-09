package jpedfilter.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException; 

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import jpedfilter.exception.VCFParseException;
import jpedfilter.genotype.GenotypeFactoryA;
import jpedfilter.genotype.SingleGenotypeFactory;
import jpedfilter.genotype.MultipleGenotypeFactory;

/**
 * Parses a VCF file and extracts Variants from each of the variant lines of
 * the VCF file. We read and count all of the lines that are
 * represented in the VCF file. 
 * <P>
 * We note that this parser demands that there be a FORMAT field and at least one sample id. 
 * Although this is not required in general for VCF files, any VCF file being used for 
 * exome analysis needs to have these fields. Here is the description from the VCF format
 * description at http://www.1000genomes.org:
 * <P>
 * If genotype information is present, then the same types of data must be present for all samples. 
 * First a FORMAT field is given specifying the data types and order. This is followed by one 
 * field per sample, with the colon-separated data in this field corresponding to the types 
 * specified in the format. The first sub-field must always be the genotype (GT).
 * <P>
 * The classes relies on the abstract factory pattern to create appropriate
 * {@link jpedfilter.genotype.GenotypeI GenotypeI}
 * objects depending on whether we have a single-sample or multiple-sample VCF file.
 * @author Peter Robinson 
 * @version 0.14 (5 May, 2013)
 */
public class VCFReader {
    /** Complete path of the VCF file being parsed */
    private String file_path=null;
    /** (Plain) basename of the VCF file being parsed. */
    private String base_filename = null;
    /** All of the lines in the original VCF header */
    private ArrayList<String> vcf_header=null;
    /** Set of all of the chromosomes that could not be parsed correctly, usually
	scaffolds such as chr11_random.*/
    private HashSet<String> badChrom=null;

    /** List of all variants parsed from this VCF file */
    private ArrayList<VCFLine> variant_list=null;
    /** Short messages describing any errors encountered in parsing the VCF file,
	useful for output messages. */ 
    private ArrayList<String> errorList=null;
   
    /** The total number of lines with variants.*/
    private int total_number_of_variants;
    
    /** Factory object to create {@link jpedfilter.genotype.GenotypeI GenotypeI} objects. Note that
     * this is an abstract class that will be instantiated depending on the information in the
     * #CHROM line of the VCF file (especially, whether there is a single sample or multiple
     * samples).
     */
    private GenotypeFactoryA genofactory=null;

    /** List of codes for FORMAT field in VCF */
    /** Genotype field  */
    public static final int FORMAT_GENOTYPE = 1;
    /* Genotype Quality field for FORMAT*/
    public static final int FORMAT_GENOTYPE_QUALITY = 2;
    /* Likelihoods for RR,RA,AA genotypes (R=ref,A=alt)  for FORMAT*/
    public static final int FORMAT_LIKELIHOODS = 3;
    /** # high-quality bases for FORMAT */
    public static final int FORMAT_HIGH_QUALITY_BASES = 4;
    /** Phred-scaled strand bias P-value  (FORMAT)*/
    public static final int FORMAT_PHRED_STRAND_BIAS = 5;
    /** List of Phred-scaled genotype likelihoods (FORMAT)*/
    public static final int FORMAT_PHRED_GENOTYPE_LIKELIHOODS = 6; 

    
    /** List of samples on this VCF file. */
    private ArrayList<String> sample_name_list = null;
    /** List of lines that could not be successfully parsed.
    * This list can be used for user output.
    */
    private ArrayList<String> unparsable_line_list=null;

     /** @return The total number of variants of any kind parsed from the VCF file*/
    public int get_total_number_of_variants() { return this.total_number_of_variants;}
    

    /**
     * The constructor initializes various ArrayLists etc. After calling the constructors,
     * users can call parse_file(String path) to 
     */
    public VCFReader() {
	this.variant_list = new ArrayList<VCFLine>();
	this.vcf_header= new ArrayList<String>();
	this.unparsable_line_list = new ArrayList<String>();
	this.sample_name_list = new  ArrayList<String>();
	this.total_number_of_variants = 0;
	this.badChrom = new HashSet<String>();
	this.errorList = new ArrayList<String>();
    }

    /**
     * @return a list of variants extracted from the VCF file. 
     */
    public ArrayList<VCFLine> getVariantList() { return this.variant_list;} 

    /**
     * @return List of sample names
     */
    public ArrayList<String> getSampleNames(){ return this.sample_name_list; }

    /**
     * @return A list of VCF lines that could not be parsed correctly.
     */
    public ArrayList<String> getListOfUnparsableLines() { return this.unparsable_line_list; }
    
    /**
     * The parsing process stores a list with each of the header lines of the original VCF file.
     * @return List of lines of the original VCF file.
     */
    public ArrayList<String> get_vcf_header() { return vcf_header; }

    /**
     * @return list of any errors encountered during VCF parsing, or  null to indicate no error.
     */
    public ArrayList<String> get_html_message() { 
	ArrayList<String> msg = new ArrayList<String>();
	msg.add(String.format("VCF file: %s (number of variants: %d)",base_filename,this.total_number_of_variants));
	if (this.errorList.size() != 0) {
	    msg.add("Errors encountered while parsing VCF file:");
	    msg.addAll(this.errorList);
	}
	if (this.unparsable_line_list.size()!=0) {
	    msg.add("Could not parse the following lines:");
	    msg.addAll(this.unparsable_line_list);
	}
	return msg;
    }

    /**
     * Parse a VCF file that has been put into a BufferedReader by
     * client code (one possible use: a tomcat server). The result is the same
     * as if we passed the file path to the method {@link #parseFile} but
     * is useful in cases where we have a BufferedReader but not a file on disk.
     */
    public void parseStringStream(BufferedReader VCFfileContents) throws VCFParseException {
	try{
	    inputVCFStream(VCFfileContents);
	} catch (IOException e) {
	    String err =
	      String.format("[VCFReader:parseStringStream]: %s",e.toString());
	    throw new VCFParseException(err);
	 }
    }


    /**
     * This method parses the entire VCF file.
     * It places all header lines into the arraylist "header" and the remaining lines are parsed into
     * Variant objects.
     */
     public void parseFile(String VCFfilePath) throws VCFParseException {
	 this.file_path = VCFfilePath;
	 File file = new File(this.file_path);
	 this.base_filename = file.getName();
	 try{
	     FileInputStream fstream = new FileInputStream(this.file_path);
	     DataInputStream in = new DataInputStream(fstream);
	     BufferedReader br = new BufferedReader(new InputStreamReader(in));
	     inputVCFStream(br);
	 } catch (IOException e) {
	    String err = String.format("[VCFReader:parseFile]: %s",e.toString());
	    throw new VCFParseException(err);
	 }
     }

    /**
     * Parse the entire VCF file. Note that for now, we merely store the header lines
     * of the file in an ArrayList. This class could be improved by storing various
     * data elements/explanations explicitly.
     * @param br An open handle to a VCF file.
     */
    private void inputVCFStream(BufferedReader br)
	throws IOException, VCFParseException
    {	
	String line;
	int linecount=0;
	int snvcount=0;
	// The first line of a VCF file should include the VCF version number
	// e.g., ##fileformat=VCFv4.0
	line = br.readLine();
	if (line == null) {
	    String err =
		String.format("Error: First line of VCF file (%s) was null",
				this.file_path);
	    throw new VCFParseException(err);
	}
	if (!line.startsWith("##fileformat=VCF")) {
	    String err = "Error: First line of VCF file did not start with format:" + line;
	    throw new VCFParseException(err);
	} else {
	    vcf_header.add(line);
	}
	String version = line.substring(16).trim();
		
	while ((line = br.readLine()) != null)   {
	    if (line.isEmpty()) continue;
	    if (line.startsWith("##")) {
		vcf_header.add(line); 
		continue; 
	    } else if (line.startsWith("#CHROM")) {
		/* The CHROM line is the last line of the header and
		  includes  the FORMAT AND sample names. */
		try {
		    parse_chrom_line(line); 
		    vcf_header.add(line); 
		} catch (VCFParseException e) {
		    String s = String.format("Error parsing #CHROM line: %s",e.toString());
		    throw new VCFParseException(s);
		}
		/* Note that a side effect of the function parse_chrom_line
		   is to add sample names to sample_name_map. We can now instantiate the
		   genotype factory depending on whether there is more than one sample.
		*/
		int n = this.sample_name_list.size();
		if (n == 1) {
		    this.genofactory = new SingleGenotypeFactory();
		} else {
		    this.genofactory = new MultipleGenotypeFactory();
		} 
		break; /* The #CHROM line is the last line of the header */ 
	    }
	}
	/* This tells VCFLine whether to expect single-sample or multiple-sample.*/
	VCFLine.setGenotypeFactory(genofactory);
	/* Here is where we begin to parse the variant lines! */
	while ((line = br.readLine()) != null)   {
	    if (line.isEmpty()) continue;
	    VCFLine ln = null;
	    try {
		ln = new VCFLine(line);
	    } catch (VCFParseException e) {
		/* Note: Do not propagate these exceptions further, but
		  * merely record what happened. */ 
		this.unparsable_line_list.add(e + ": " + line);      
		System.err.println("Warning: Skipping unparsable line: \n\t" + line);
		System.err.println("Exception: "+e.toString());
		continue;
	    }
	    this.total_number_of_variants++;    
	    variant_list.add(ln);
	} // while
	if (this.badChrom.size()>0) {
	    recordBadChromosomeParses();
	}
    }
	

    /**
     * This function gets called when there was difficulty in 
     * parsing the chromosomes of some variants, e.g., GL000192.1.
     * We add a list of the chromosomes to messages, this can be used
     * to produce error messages for user output.
     */
    private void recordBadChromosomeParses()
    {
	Iterator<String> it = this.badChrom.iterator();
	while (it.hasNext()) {
	    String s = it.next();
	    String t = String.format("Could not parse variant(s) mapped to chromosome: %s",s);
	    this.errorList.add(t);
	}
    }


    /**
     * The #CHROM line is the last line of the header of a VCF file, and it contains
     * seven required fields followed by one or more sample names.
     * <PRE>
     * #CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	name1  name2 ...
     * </PRE>
     * We will use the number of sample names to determine which subclass of the abstract
     * factory {@link jpedfilter.genotype.GenotypeFactoryA GenotypeFactoryA} to instantiate.
     */
    public void parse_chrom_line(String line) throws VCFParseException
    {
	String A[] = line.split("\t");
	/* First check that obligatory format is correct */

	this.errorList.add("VCF File: " + this.base_filename + ".");
	if (! A[0].equals("#CHROM") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed #CHROM field in #CHROM line: " + line);
	}
	if (! A[1].equals("POS") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed POS field in #CHROM line:" + line);
	}
	if (! A[2].equals("ID") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed ID field in #CHROM line:" + line);
	}
	if (! A[3].equals("REF") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed REF field in #CHROM line:" + line);
	}
	if (! A[4].equals("ALT") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed ALT field in #CHROM line:" + line);
	}
	if (! A[5].equals("QUAL") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed QUAL field in #CHROM line:" + line);
	}
	if (! A[6].equals("FILTER") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed FILTER field in #CHROM line:" + line);
	}
	if (! A[7].equals("INFO") ) {
	    throw new VCFParseException("[parse_chrom_line]: Malformed INFO field in #CHROM line:" + line);
	}
	if (! A[8].equals("FORMAT") ) {
	    String s = String.format("[parse_chrom_line]: Malformed FORMAT field in #CHROM line: %s",line);
	    throw new VCFParseException(s);
	}
	if (A.length<10) {
	    String s = String.format("Error: Did not find sufficient number fields in the #CHROM line" +
				     " (need to be at least 10, but found %d): %s",A.length,line);
	    throw new VCFParseException(s);
	}
	/* Note that if we get here, the sample names must begin in field 9 */
	for (int i=9; i< A.length; ++i) {
	    sample_name_list.add(A[i]);
	}

    } 
}
/* eof */