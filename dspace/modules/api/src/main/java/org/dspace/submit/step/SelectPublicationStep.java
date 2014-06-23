package org.dspace.submit.step;

import com.Ostermiller.util.CircularByteBuffer;
import org.apache.log4j.Logger;
import org.dspace.content.*;
import org.dspace.content.authority.Choices;
import org.dspace.content.crosswalk.IngestionCrosswalk;
import org.dspace.core.LogManager;
import org.dspace.core.PluginManager;
import org.dspace.submit.AbstractProcessingStep;
import org.dspace.core.Context;
import org.dspace.core.ConfigurationManager;
import org.dspace.app.util.SubmissionInfo;
import org.dspace.authorize.AuthorizeException;
import org.dspace.submit.bean.PublicationBean;
import org.dspace.submit.model.ModelPublication;
import org.dspace.submit.utils.DryadJournalSubmissionUtils;
import org.dspace.workflow.WorkflowRequirementsManager;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import org.dspace.usagelogging.EventLogger;

/**
 * User: @author kevinvandevelde (kevin at atmire.com)
 * Date: 27-jan-2010
 * Time: 15:12:46
 *
 * The processing of the first step in which a journal can be selected.
 */
public class SelectPublicationStep extends AbstractProcessingStep {

    public static final int STATUS_INVALID_PUBLICATION_ID = 1;
    public static final int STATUS_LICENSE_NOT_ACCEPTED = 2;
    public static final int ERROR_SELECT_JOURNAL = 3;
    public static final int ERROR_INVALID_JOURNAL = 4;
    public static final int ERROR_PUBMED_DOI = 8;
    public static final int ERROR_GENERIC = 9;
    public static final int ERROR_PUBMED_NAME = 11;


    public static final int DISPLAY_MANUSCRIPT_NUMBER = 5;
    public static final int DISPLAY_CONFIRM_MANUSCRIPT_ACCEPTANCE = 6;
    public static final int ENTER_MANUSCRIPT_NUMBER = 7;
    public static final int ERROR_SELECT_COUNTRY = 10;



    private static Logger log = Logger.getLogger(SelectPublicationStep.class);


    public final static int  ARTICLE_STATUS_PUBLISHED=0;
    public final static int  ARTICLE_STATUS_ACCEPTED=1;
    public final static int  ARTICLE_STATUS_IN_REVIEW=2;
    public final static int  ARTICLE_STATUS_NOT_YET_SUBMITTED=3;


    public int doProcessing(Context context, HttpServletRequest request, HttpServletResponse response, SubmissionInfo submissionInfo) throws ServletException, IOException, SQLException, AuthorizeException {
	log.debug("processing new submission request");
	
        try{

            Item item = submissionInfo.getSubmissionItem().getItem();

            String journalID = null;
            String articleStatus = request.getParameter("article_status");
            String manuscriptNumber = request.getParameter("manu");

	    // get the journalID selected by the user in the UI
            if(articleStatus!=null){
                if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_ACCEPTED){
                    String manuscriptNumberAcc = request.getParameter("manu-number-status-accepted");
                    String manuAcc = request.getParameter("manu_acc");
                    manuscriptNumber = manuscriptNumberAcc;
		            manuscriptNumber = manuscriptNumber.trim();

                    String journalName = request.getParameter("prism_publicationName");
                    journalName=journalName.replace("*", "");
		            journalName=journalName.trim();
                    Concept concept = DryadJournalSubmissionUtils.findKeyByFullname(context,journalName);
                    if(concept!=null){
                        journalID = concept.getIdentifier();
                    }
                    if(journalID==null) journalID=journalName;
                }
                else if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_NOT_YET_SUBMITTED){
                    journalID = request.getParameter("journalIDStatusNotYetSubmitted");
                }
                else if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_IN_REVIEW){
                    journalID = request.getParameter("journalIDStatusInReview");
                }
            }

            EventLogger.log(context, "submission-select-publication", "journalID=" + journalID +
                    ",articleStatus=" + articleStatus + ",manuscriptNumber=" + manuscriptNumber);

            //First of all check if we have accepted our license
            if(request.getParameter("license_accept") == null || !Boolean.valueOf(request.getParameter("license_accept"))) {
                EventLogger.log(context, "submission-select-publication", "error=failed_license_accept");
                return STATUS_LICENSE_NOT_ACCEPTED;
            }
	    // attempt to process a DOI or PMID entered in the UI
            if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_PUBLISHED){
                String identifier = request.getParameter("article_doi");
                String journal = request.getParameter("unknown_doi");
                if(identifier!=null && !identifier.equals("")){

                    if(identifier.indexOf('/')!=-1){
                        if(!processDOI(context, item, identifier)) {
                            EventLogger.log(context, "submission-select-publication", "doi=" + identifier + ",error=failed_doi_lookup");
                            return ERROR_PUBMED_DOI;
                        } else {
                            EventLogger.log(context, "submission-select-publication", "doi=" + identifier);
                        }
                    }
                    else{
                       if(!processPubMed(context, item, identifier)) {
                           EventLogger.log(context, "submission-select-publication", "pmid=" + identifier + ",error=failed_pubmed_lookup");
                            return ERROR_PUBMED_DOI;
                       } else {
                           EventLogger.log(context, "submission-select-publication", "pmid=" + identifier);
                       }
                    }
                }
                else
                {
                    if(journal==null||journal.length()==0)
                    {
                        EventLogger.log(context, "submission-select-publication", "error=no_journal_name");
                        return ERROR_PUBMED_NAME;
                    }
                    else{
                        journal=journal.replace("*", "");
                        journal=journal.trim();
                        Concept concept = DryadJournalSubmissionUtils.findKeyByFullname(context,journal);
                        if(concept!=null){
                            journalID = concept.getIdentifier();
                        }
                        if(journalID==null) journalID=journal;
                        if(journalID==null||journalID.equals("")){
                            EventLogger.log(context, "submission-select-publication", "error=invalid_journal");
                            return ERROR_INVALID_JOURNAL;
                        }
                        else if(!processJournal(journalID, manuscriptNumber, item, context, request, articleStatus)){

                            if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_ACCEPTED) return ENTER_MANUSCRIPT_NUMBER;

                            EventLogger.log(context, "submission-select-publication", "error=no_journal_selected");
                            return ERROR_SELECT_JOURNAL;
                        }
                    }
                }

            }
	    
            // ARTICLE_STATUS_ACCEPTED ||  ARTICLE_STATUS_IN_REVIEW ||  ARTICLE_STATUS_NOT_YET_SUBMITTED
            else{
                if(journalID==null||journalID.equals("")){
                    EventLogger.log(context, "submission-select-publication", "error=invalid_journal");
                    return ERROR_INVALID_JOURNAL;
                }
                else if(!processJournal(journalID, manuscriptNumber, item, context, request, articleStatus)){

                    if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_ACCEPTED) return ENTER_MANUSCRIPT_NUMBER;

                    EventLogger.log(context, "submission-select-publication", "error=no_journal_selected");
                    return ERROR_SELECT_JOURNAL;
                }
            }
            EventLogger.log(context, "submission-select-publication", "status=complete");

            return STATUS_COMPLETE;
        }catch(Exception e){
            log.error(e);
        }
        EventLogger.log(context, "submission-select-publication", "error=exception_reselect_journal");
        return ERROR_SELECT_JOURNAL;
    }

    /**
       Process a DOI entered by the submitter. Use the DOI metadata to initialize publication information.
     **/
    private boolean processDOI(Context context, Item item, String identifier){

	// normalize and validate the identifier
	identifier = identifier.toLowerCase().trim();
        if(identifier.startsWith("doi:")) {
            identifier = identifier.replaceFirst("doi:", "");
	}
	
        try{
            Element jElement = retrieveXML("http://api.labs.crossref.org/" + identifier + ".xml");
            if(jElement != null){

                List<Element> children = jElement.getChildren();
                if(children.size()==0){
                    return false;
                }

                if(!isAValidDOI(jElement)) return false;

                // Use the ingest process to parse the XML document, transformation is done
                // using XSLT
                IngestionCrosswalk xwalk = (IngestionCrosswalk) PluginManager.getNamedPlugin(IngestionCrosswalk.class, "DOI");

                xwalk.ingest(context, item, jElement);
                return true;
            }
        }catch (Exception ex){
            log.error("unable to process DOI metadata", ex);
            return false;
        }
        return false;

    }


    /**
       Process a PMID entered by the submitter. Use the PMID metadata to initialize publication information.
     **/
    private boolean processPubMed(Context context, Item item, String identifier){

	// normalize and validate the identifier
	identifier = identifier.toLowerCase().trim();
	if(identifier.startsWith("pmid: ")) {
	    identifier = identifier.substring("pmid: ".length());
	}
	if(identifier.startsWith("pmid ")) {
	    identifier = identifier.substring("pmid ".length());
	}
	if(identifier.startsWith("pmid:")) {
	    identifier = identifier.substring("pmid:".length());
	}
	if(identifier.startsWith("pmid")) {
	    identifier = identifier.substring("pmid".length());
	}
        if(!isValidPubmedID(identifier)) return false;

        try{
            Element jElement = retrieveXML("http://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pubmed&retmode=xml&id=" + identifier);
            if(jElement != null){

                List<Element> children = jElement.getChildren();
                if(jElement.getName().equals("ERROR") || children.size()==0){
                    return false;
                }

                // Use the ingest process to parse the XML document, transformation is done
                // using XSLT
                IngestionCrosswalk xwalk = (IngestionCrosswalk) PluginManager.getNamedPlugin(IngestionCrosswalk.class, "PUBMED");
                xwalk.ingest(context, item, jElement);
                return true;
            }
        }catch (Exception ex){
            log.error("unable to process PMID metadata", ex);
            return false;
        }
        return false;
    }

    private Element retrieveXML(String urls) throws Exception{
        SAXBuilder builder = new SAXBuilder();
        org.jdom.Document doc = builder.build(urls);
        return doc.getRootElement();
    }



    private boolean isValidPubmedID(String pmid){
        try{
            // A valid PMID will be parseable as an integer
            return (Integer.parseInt(pmid, 10) > 0);
        }
        catch (NumberFormatException nfe){
            return false;
        }
    }


    private static boolean isAValidDOI(Element element) {
        List<Element> children = element.getChildren();
        for(Element e : children){
            if(e.getName().equals("doi_record")){
                List<Element> doiRecordsChildren = e.getChildren();
                for(Element e1 : doiRecordsChildren){

                    if(e1.getName().equals("crossref")){
                        List<Element> crossRefChildren = e1.getChildren();
                        for(Element e2 : crossRefChildren){
                            if(e2.getName().equals("error")){
                                return false;
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return true;
    }


    private boolean processJournal(String journalID, String manuscriptNumber, Item item, Context context,
				   HttpServletRequest request, String articleStatus) throws AuthorizeException, SQLException {
	String title = journalID; // Preserve the case of the original entry
	journalID = journalID.toLowerCase();

    //Map<String,String> journalProperties = DryadJournalSubmissionUtils.getPropertiesByJournal(context,journalID);
	
	log.debug("processing journal ID " + journalID);
	
        //We have selected to choose a journal, retrieve it
        if(!journalID.equals("other")){
            if(!DryadJournalSubmissionUtils.isIntegrated(context,journalID) || (DryadJournalSubmissionUtils.isIntegrated(context,journalID) && manuscriptNumber != null && manuscriptNumber.trim().equals(""))){
		        log.debug(journalID + " is not integrated OR manuscript number is null");
                //Just add the journal title
                if(DryadJournalSubmissionUtils.exists(context,journalID)){
                    title = DryadJournalSubmissionUtils.getJournalName(context,journalID);
                    //Should it end with a *, remove it.
                    if(title.endsWith("*"))
                        title = title.substring(0, title.length() - 1);

                    Boolean embargoAllowed = Boolean.valueOf(DryadJournalSubmissionUtils.getJournalEmbargoAllowed(context,journalID));
                    if(!embargoAllowed){
                        //We don't need to show the embargo option to any of our data files
                        item.addMetadata("internal", "submit", "showEmbargo", null, String.valueOf(embargoAllowed));
                    }
                }
                item.addMetadata("prism", "publicationName", null, null, title, journalID, Choices.CF_UNSET);
                item.update();


            }
            else {
                if(DryadJournalSubmissionUtils.exists(context,journalID)){

                    String journalPath = DryadJournalSubmissionUtils.getJournalDirectory(context,journalID);
		    log.debug("journalPath: " + journalPath);
                    //We have a valid journal
                    // Unescape the manuscriptNumber to get the filename
                    String fileName = DryadJournalSubmissionUtils.unescapeFilename(manuscriptNumber);
                    PublicationBean pBean = ModelPublication.getDataFromPublisherFile(fileName, journalID, journalPath);
                    if (pBean.getMessage().equals((""))) {

                        // check if the status is "in review" or "rejected"
                        if(articleStatus!=null){

                            // case "Accepted"/ARTICLE_STATUS_ACCEPTED
                            // if the publication status is:
                            //    - Rejected ==> return "Invalid manuscript number."
                            //    - In Review ==> return "Invalid manuscript number."
                            //    - all the others ==> go through entering in PublicationDescriptionStep
                            if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_ACCEPTED){
                                if(pBean.getStatus()!=null && (pBean.getStatus().equals(PublicationBean.STATUS_IN_REVIEW) || pBean.getStatus().equals(PublicationBean.STATUS_REJECTED))){
                                    request.getSession().setAttribute("submit_error", "Invalid manuscript number.");
                                    return false;
                                }
                            }
                            // case "IN Review"/ARTICLE_STATUS_IN_REVIEW
                            // if the publication status is:
                            //    - Rejected ==> return "Invalid manuscript number."
                            //    - all the others ==> go through entering in PublicationDescriptionStep
                            else if(Integer.parseInt(articleStatus)==ARTICLE_STATUS_IN_REVIEW){
                                if(pBean.getStatus()!=null && pBean.getStatus().equals(PublicationBean.STATUS_REJECTED)){
                                    request.getSession().setAttribute("submit_error", "Invalid manuscript number.");
                                    return false;
                                }
                            }
                        }

                        importJournalMetadata(context, item, pBean);
                        String[] reviewEmails = DryadJournalSubmissionUtils.getJournalNotifyOnReview(context,journalID);
                        if(reviewEmails!=null&&reviewEmails.length>0){
                            item.addMetadata(WorkflowRequirementsManager.WORKFLOW_SCHEMA, "review", "mailUsers", null, reviewEmails);
                        }

                        String[] archiveEmails = DryadJournalSubmissionUtils.getJournalNotifyOnArchive(context,journalID);
                        if(archiveEmails!=null&&archiveEmails.length>0){
                            item.addMetadata(WorkflowRequirementsManager.WORKFLOW_SCHEMA, "archive", "mailUsers", null, archiveEmails);
                        }
                        boolean embargoAllowed = DryadJournalSubmissionUtils.getJournalEmbargoAllowed(context,journalID);
                        if(!embargoAllowed){
                            //We don't need to show the embargo option to any of our data files
                            item.addMetadata("internal", "submit", "showEmbargo", null, String.valueOf(embargoAllowed));
                        }
                        item.update();
                    }else{
                        request.getSession().setAttribute("submit_error", pBean.getMessage());
                        return false;
                    }
                }
            }
        }
        return true;
    }


    /**
       Import metadata from the journal settings into the data package item. If data already exists in
       the pBean, it will take precedence over the journal metadata.
     **/
    private void importJournalMetadata(Context context, Item item, PublicationBean pBean){
        // These values are common to both Article Types
        addSingleMetadataValueFromJournal(context, item, "journalName", pBean.getJournalName());
        addSingleMetadataValueFromJournal(context, item, "journalVolume", pBean.getJournalVolume());
        addSingleMetadataValueFromJournal(context, item, "abstract", pBean.getAbstract());
        addSingleMetadataValueFromJournal(context, item, "correspondingAuthor", pBean.getCorrespondingAuthor());
        addSingleMetadataValueFromJournal(context, item, "doi", pBean.getDOI());
        addMultiMetadataValueFromJournal(context, item, "authors", pBean.getAuthors());
        addMultiMetadataValueFromJournal(context, item, "subjectKeywords", pBean.getSubjectKeywords());
        addMultiMetadataValueFromJournal(context, item, "taxonomicNames", pBean.getTaxonomicNames());
        addMultiMetadataValueFromJournal(context, item, "coverageSpatial", pBean.getCoverageSpatial());
        addMultiMetadataValueFromJournal(context, item, "coverageTemporal", pBean.getCoverageTemporal());
        addSingleMetadataValueFromJournal(context, item, "publicationDate", pBean.getPublicationDate());
        addSingleMetadataValueFromJournal(context, item, "journalISSN", pBean.getJournalISSN());
        addSingleMetadataValueFromJournal(context, item, "journalNumber", pBean.getJournalNumber());
        addSingleMetadataValueFromJournal(context, item, "publisher", pBean.getPublisher());
        addSingleMetadataValueFromJournal(context, item, "manuscriptNumber", pBean.getManuscriptNumber());
        addSingleMetadataValueFromJournal(context, item, "journalID", pBean.getJournalID());
        addSingleMetadataValueFromJournal(context, item, "status", String.valueOf(pBean.isSkipReviewStep()));
        
        // These values differ based on the Article Type
        if(pBean.getArticleType().equals(PublicationBean.TYPE_GR_NOTE)) {
            final String title = String.format("\"%s\" in %s", pBean.getTitle(), pBean.getCitationTitle());
            addSingleMetadataValueFromJournal(context, item, "title", title);
            addSingleMetadataValueFromJournal(context, item, "citationTitle", pBean.getCitationTitle());
            // Citation Authors are not stored in the Item
        } else { // Assume Regular
            addSingleMetadataValueFromJournal(context, item, "title", pBean.getTitle());
        }
        String userInfo = "journal_id=" + pBean.getJournalID() + ",ms=" + pBean.getManuscriptNumber() + "";
        EventLogger.log(context, "submission-import-metadata", userInfo);
    }


    private void addSingleMetadataValueFromJournal(Context ctx, Item publication, String key, String value){
        DCValue dcVal = DryadJournalSubmissionUtils.getJournalMetadata(ctx,key);
        if(dcVal == null){
            log.error(LogManager.getHeader(ctx, "error importing field from journal", "Could not retrieve a metadata field for journal getter: " + key));
            return;
        }

        if(value != null)
            publication.addMetadata(dcVal.schema, dcVal.element, dcVal.qualifier, null, value);
    }

    private void addMultiMetadataValueFromJournal(Context ctx, Item publication, String key, List<String> values){
        DCValue dcVal = DryadJournalSubmissionUtils.getJournalMetadata(ctx,key);
        if(dcVal == null){
            log.error(LogManager.getHeader(ctx, "error importing field from journal", "Could not retrieve a metadata field for journal getter: " + key));
            return;
        }

        if(values != null && 0 < values.size())
            publication.addMetadata(dcVal.schema, dcVal.element, dcVal.qualifier, null, values.toArray(new String[values.size()]));
    }

    

    public int getNumberOfPages(HttpServletRequest request, SubmissionInfo submissionInfo) throws ServletException {
        return 1;
    }

    @Override
    public boolean isStepAccessible(Context context, Item item) {
        //If we already have a handle there is no need to use this step
        boolean stepAccessible = true;
        if(item.getHandle() == null){
            try {
                WorkspaceItem wsItem = WorkspaceItem.findByItemId(context, item.getID());
                if(wsItem != null){
                    //Only allow this step if the user hasn't passed it
                    stepAccessible = 1 == wsItem.getStageReached() || -1 == wsItem.getStageReached();
                }
            } catch (SQLException e) {
                log.error("Error in isStepAccessible: " + e.getMessage(), e);
            }
        }else{
            stepAccessible = false;
        }

        return stepAccessible;
    }


}
