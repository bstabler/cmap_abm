package com.pb.models.ctrampIf.jppf;

import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;

import com.pb.common.calculator.VariableTable;
import com.pb.common.datafile.TableDataSet;
import com.pb.models.ctrampIf.CtrampDmuFactoryIf;
import com.pb.models.ctrampIf.HouseholdIf;
import com.pb.models.ctrampIf.TourModeChoiceDMU;
import com.pb.models.ctrampIf.ModelStructure;
import com.pb.models.ctrampIf.PersonIf;
import com.pb.models.ctrampIf.TourDepartureTimeAndDurationDMU;
import com.pb.models.ctrampIf.TourIf;
import com.pb.models.ctrampIf.Util;
import com.pb.common.newmodel.ChoiceModelApplication;

/**
 * Created by IntelliJ IDEA.
 * User: Jim
 * Date: Jul 11, 2008
 * Time: 9:25:30 AM
 * To change this template use File | Settings | File Templates.
 */
public class NonMandatoryTourDepartureAndDurationTime implements Serializable {

    
    private transient Logger logger = Logger.getLogger( NonMandatoryTourDepartureAndDurationTime.class );
    private transient Logger todLogger = Logger.getLogger( "todLogger" );

    private static final String INMTOD_UEC_FILE_TARGET          = "UecFile.TourDepartureTimeAndDuration";
    private static final String INMTOD_UEC_DATA_TARGET          = "departTime.data.page";
    private static final String INMTOD_UEC_ESCORT_MODEL_TARGET  = "departTime.escort.page";
    private static final String INMTOD_UEC_SHOP_MODEL_TARGET    = "departTime.shop.page";
    private static final String INMTOD_UEC_MAINT_MODEL_TARGET   = "departTime.maint.page";
    private static final String INMTOD_UEC_EAT_MODEL_TARGET     = "departTime.eat.page";
    private static final String INMTOD_UEC_VISIT_MODEL_TARGET   = "departTime.visit.page";
    private static final String INMTOD_UEC_DISCR_MODEL_TARGET   = "departTime.discr.page";

    
    private static final String[] tourPurposeNames = {
        ModelStructure.ESCORT_PRIMARY_PURPOSE_NAME,
        ModelStructure.SHOPPING_PRIMARY_PURPOSE_NAME,
        ModelStructure.OTH_MAINT_PRIMARY_PURPOSE_NAME,
        ModelStructure.EAT_OUT_PRIMARY_PURPOSE_NAME,
        ModelStructure.VISITING_PRIMARY_PURPOSE_NAME,
        ModelStructure.OTH_DISCR_PRIMARY_PURPOSE_NAME 
    };
    
    private static final String[] dcModelSheetKeys = {
        INMTOD_UEC_ESCORT_MODEL_TARGET,
        INMTOD_UEC_SHOP_MODEL_TARGET,
        INMTOD_UEC_MAINT_MODEL_TARGET,
        INMTOD_UEC_EAT_MODEL_TARGET,
        INMTOD_UEC_VISIT_MODEL_TARGET,
        INMTOD_UEC_DISCR_MODEL_TARGET
    };


    // process non-mandatory tours in order by priority purpose:
    // 4=escort, 6=oth maint, 5=shop, 8=visiting, 9=oth discr, 7=eat out, 
    private static final int[] tourPurposeIndexOrder = {
        4,
        6,
        5,
        8,
        9,
        7
    };

    private ArrayList<TourIf>[] purposeTourLists;    
    
    private int[] todModelIndices;
    private HashMap<String, Integer> purposeNameIndexMap;


    
    private int[] tourDepartureTimeChoiceSample;


    // DMU for the UEC
    private TourDepartureTimeAndDurationDMU todDmuObject;
    private TourModeChoiceDMU mcDmuObject;

    // model structure to compare the .properties time of day with the UECs
    private ModelStructure modelStructure;
    private String tourCategory;
    
    private ChoiceModelApplication[] todModels;
    private ModeChoiceModel mcModel;

    private int[] altStarts;
    private int[] altEnds;    

    private boolean[] needToComputeLogsum;
    private double[] modeChoiceLogsums;

    private int noAltChoice = 1;




    public NonMandatoryTourDepartureAndDurationTime( HashMap<String, String> propertyMap, ModelStructure modelStructure, CtrampDmuFactoryIf dmuFactory, String tourCategory, ModeChoiceModel mcModel ){

        // set the model structure
        this.modelStructure = modelStructure;
        this.mcModel = mcModel;
        this.tourCategory = tourCategory;
        
        setUpModels( propertyMap, dmuFactory );
    }


    public void setUpModels( HashMap<String, String> propertyMap, CtrampDmuFactoryIf dmuFactory ) {

        logger.info( String.format( "setting up %s time-of-day choice model.", tourCategory ) );

        String projectDirectory = propertyMap.get( CtrampApplication.PROPERTIES_PROJECT_DIRECTORY );

        String todUecFileName = propertyMap.get( INMTOD_UEC_FILE_TARGET );
        todUecFileName = projectDirectory + todUecFileName;

        todDmuObject = dmuFactory.getTourDepartureTimeAndDurationDMU();
        mcDmuObject = dmuFactory.getModeChoiceDMU();
    
        int numLogsumIndices = modelStructure.getPeriodCombinationIndices().length;
        needToComputeLogsum = new boolean[numLogsumIndices];
        modeChoiceLogsums = new double[numLogsumIndices];


        // create the array of tod model indices
        int[] uecSheetIndices = new int[tourPurposeNames.length];
        
        purposeNameIndexMap = new HashMap<String,Integer>(tourPurposeNames.length);
        
        int i = 0;
        for ( String purposeName : tourPurposeNames ) {
            int uecIndex = Util.getIntegerValueFromPropertyMap( propertyMap, dcModelSheetKeys[i]);
            purposeNameIndexMap.put(purposeName.toLowerCase(), i);
            uecSheetIndices[i] = uecIndex;
            i++;
        }
        
        // create a lookup array to map purpose index to model index
        todModelIndices = new int[uecSheetIndices.length];

        // get a set of unique model sheet numbers so that we can create ChoiceModelApplication objects once for each model sheet used
        // also create a HashMap to relate size segment index to SOA Model objects 
        HashMap<Integer,Integer> modelIndexMap = new HashMap<Integer,Integer>();
        int todModelIndex = 0;
        for ( int uecIndex : uecSheetIndices ) {
            // if the uec sheet for the model segment is not in the map, add it, otherwise, get it from the map
            if ( ! modelIndexMap.containsKey(uecIndex) ) {
                modelIndexMap.put( uecIndex, todModelIndex );
                todModelIndices[todModelIndex] = todModelIndex++;
            }
            else {
                todModelIndices[todModelIndex++] = modelIndexMap.get( uecIndex );
            }
        }

        
        todModels = new ChoiceModelApplication[modelIndexMap.size()];
        int todModelDataSheet = Util.getIntegerValueFromPropertyMap( propertyMap, INMTOD_UEC_DATA_TARGET );

        for (int uecIndex : modelIndexMap.keySet() )
        {
            int modelIndex = modelIndexMap.get(uecIndex);
            try
            {
                todModels[modelIndex] = new ChoiceModelApplication( todUecFileName, uecIndex, todModelDataSheet, propertyMap, (VariableTable) todDmuObject);
            } catch (RuntimeException e)
            {
                logger.error( String.format("exception caught setting up NonMandatory TOD ChoiceModelApplication[%d] for modelIndex=%d, num choice models=%d", modelIndex, modelIndex, modelIndexMap.size()) );
                logger.fatal("Exception caught:", e);
                logger.fatal("Throwing new RuntimeException() to terminate.");
                throw new RuntimeException();
            }

        }


        // get the alternatives table from the work tod UEC.
        TableDataSet altsTable = todModels[0].getUEC().getAlternativeData();
        
        altStarts = altsTable.getColumnAsInt( CtrampApplication.START_FIELD_NAME );
        altEnds = altsTable.getColumnAsInt( CtrampApplication.END_FIELD_NAME );
        todDmuObject.setTodAlts(altStarts, altEnds);

        int numDepartureTimeChoiceAlternatives = todModels[0].getNumberOfAlternatives();
        tourDepartureTimeChoiceSample = new int[numDepartureTimeChoiceAlternatives+1];
        Arrays.fill ( tourDepartureTimeChoiceSample, 1 );

        // allocate an array of ArrayList objects to hold tour lists by purpose - tour lists will be processed 
        // in priority purpose order.
        int maxPurposeIndex = 0;
        for ( i=0; i < tourPurposeIndexOrder.length; i++ )
            if ( tourPurposeIndexOrder[i] > maxPurposeIndex )
                maxPurposeIndex = tourPurposeIndexOrder[i];
        
        purposeTourLists = new ArrayList[maxPurposeIndex+1];
        for ( i=0; i < tourPurposeIndexOrder.length; i++ ) {
            int index = tourPurposeIndexOrder[i];
            purposeTourLists[index] = new ArrayList<TourIf>();
        }

    }


    public void applyIndivModel( HouseholdIf hh, boolean runModeChoice ){

        Logger modelLogger = todLogger;
        
        // set the household id, origin taz, hh taz, and debugFlag=false in the dmu
        todDmuObject.setHousehold( hh );

        
        

        // get the person objects for this household
        PersonIf[] persons = hh.getPersons();
        for ( int p=1; p < persons.length; p++ ) {

            PersonIf person = persons[p];
            
            // if no individual non-mandatory tours, nothing to do.
            if ( person.getListOfIndividualNonMandatoryTours().size() == 0 )
                continue;

            // arrange the individual non-mandatory tours for this person in an array of ArrayLists by purpose
            getPriorityOrderedTourList( person.getListOfIndividualNonMandatoryTours() );


            // process tour lists by priority purpose
            
            // define variables to hold depart/arrive periods selected for the most recent tour.
            // if a tour has no non-overlapping period available, set the periods to either the depart or arrive of the most recently determined
            // if none has been selected yet, set to the first of last interval
            int previouslySelectedDepartPeriod = -1;
            int previouslySelectedArrivePeriod = -1;
            
            for ( int i=0; i < tourPurposeIndexOrder.length; i++ ) {
                
                int tourPurposeIndex = tourPurposeIndexOrder[i];
                
                // process each individual non-mandatory tour from the list
                int m = -1;
                int tourPurpNum = 1;
                int noWindowCountFirstTemp = 0;
                int noWindowCountLastTemp = 0;
                int noLaterAlternativeCountTemp = 0;
                for ( TourIf t : purposeTourLists[tourPurposeIndex] ) {

                    try {

                        // get the choice model for the tour purpose
                        String tourPurposeName = t.getTourPrimaryPurpose();
                        m = todModelIndices[purposeNameIndexMap.get(tourPurposeName)];
                        
                        // write debug header
                        String separator = "";
                        String choiceModelDescription = "" ;
                        String decisionMakerLabel = "";
                        String loggingHeader = "";
                        if( hh.getDebugChoiceModels() ) {

                            choiceModelDescription = String.format ( "Individual Non-Mandatory Tour Departure Time Choice Model for: Purpose=%s", tourPurposeName );
                            decisionMakerLabel = String.format ( "HH=%d, PersonNum=%d, PersonType=%s, tourId=%d, num=%d of %d %s tours", hh.getHhId(), person.getPersonNum(), person.getPersonType(), t.getTourId(), tourPurpNum, purposeTourLists[tourPurposeIndex].size(), tourPurposeName );
                            
                            todModels[m].choiceModelUtilityTraceLoggerHeading( choiceModelDescription, decisionMakerLabel );
                                
                            modelLogger.info(" ");
                            String loggerString = "Individual Non-Mandatory Tour Departure Time Choice Model: Debug Statement for Household ID: " + hh.getHhId() + ", Person Num: " + person.getPersonNum() + ", Person Type: " + person.getPersonType() + ", Tour Id: " + t.getTourId() + ", num " + tourPurpNum + " of " + purposeTourLists[tourPurposeIndex].size() + " " + tourPurposeName + " tours.";
                            for (int k=0; k < loggerString.length(); k++)
                                separator += "+";
                            modelLogger.info( loggerString );
                            modelLogger.info( separator );
                            modelLogger.info( "" );
                            modelLogger.info( "" );
                         
                            loggingHeader = String.format( "%s for %s", choiceModelDescription, decisionMakerLabel );
                            
                        }

                        // set the dmu object
                        todDmuObject.setHousehold( hh );
                        todDmuObject.setPerson( person );
                        todDmuObject.setTour( t );

                        
                        // check for multiple tours for this person, by purpose
                        // set the first or second switch if multiple tours for person, by purpose
                        if ( purposeTourLists[tourPurposeIndex].size() == 1 ) {
                            // not a multiple tour pattern
                            todDmuObject.setFirstTour( 0 );
                            todDmuObject.setSubsequentTour( 0 );
                            todDmuObject.setTourNumber( 1 );
                            todDmuObject.setEndOfPreviousScheduledTour( 0 );
                        }
                        else if ( purposeTourLists[tourPurposeIndex].size() > 1 ) {
                            // Two-plus tour multiple tour pattern
                            if ( tourPurpNum == 1 ) {
                                // first of 2+ tours
                                todDmuObject.setFirstTour( 1 );
                                todDmuObject.setSubsequentTour( 0 );
                                todDmuObject.setTourNumber( tourPurpNum );
                                todDmuObject.setEndOfPreviousScheduledTour( 0 );
                            }
                            else {
                                // 2nd or greater tours
                                todDmuObject.setFirstTour( 0 );
                                todDmuObject.setSubsequentTour( 1 );
                                todDmuObject.setTourNumber( tourPurpNum );
                                // the ArrayList is 0-based, and we want the previous tour, so subtract 2 from tourPurpNum to get the right index
                                int otherTourEndHour = purposeTourLists[tourPurposeIndex].get( tourPurpNum-2 ).getTourArrivePeriod();
                                todDmuObject.setEndOfPreviousScheduledTour( otherTourEndHour );
                            }
                        }

                        

                        // set the choice availability and sample
                        boolean[] departureTimeChoiceAvailability = person.getAvailableTimeWindows( altStarts, altEnds );
                        Arrays.fill(tourDepartureTimeChoiceSample, 1);
                        
                        
                        if ( departureTimeChoiceAvailability.length != tourDepartureTimeChoiceSample.length ) {
                            logger.error( String.format( "error in individual non-mandatory departure time choice model for hhId=%d, personNum=%d, tour purpose index=%d, tour ArrayList index=%d.", hh.getHhId(), person.getPersonNum(), tourPurposeIndex, tourPurpNum-1 ));
                            logger.error( String.format( "length of the availability array determined by the number of alternatives set in the person scheduler=%d", departureTimeChoiceAvailability.length ));
                            logger.error( String.format( "does not equal the length of the sample array determined by the number of alternatives in the individual non-mandatory tour UEC=%d.", tourDepartureTimeChoiceSample.length ));
                            throw new RuntimeException();
                        }

                        
                        // if all time windows for this person have already been scheduled, choose either the first and last
                        // alternatives and keep track of the number of times this condition occurs.
                        int alternativeAvailable = -1;
                        for (int a=0; a < departureTimeChoiceAvailability.length; a++) {
                            if ( departureTimeChoiceAvailability[a] ) {
                                alternativeAvailable = a;
                                break;
                            }
                        }

                        int chosen = -1;
                        int chosenStartPeriod = -1;
                        int chosenEndPeriod = -1;
                        

                        // alternate making the first and last periods chosen if no alternatives are available
                        if ( alternativeAvailable < 0 ) {

                        	if ( noAltChoice == 1 ) {
                                if ( previouslySelectedDepartPeriod < 0 ) {
                                    chosenStartPeriod = altStarts[noAltChoice-1];
                                    chosenEndPeriod = altEnds[noAltChoice-1];
                                    if( hh.getDebugChoiceModels() )
                                        modelLogger.info( "All alternatives already scheduled, and no non-mandatory tour scheduled yet, depart AND arrive set to first period=" + chosenStartPeriod + ", " + chosenEndPeriod + "." );
                                }
                                else {
                                    chosenStartPeriod = previouslySelectedArrivePeriod;
                                    chosenEndPeriod = previouslySelectedArrivePeriod;
                                    if( hh.getDebugChoiceModels() )
                                        modelLogger.info( "All alternatives already scheduled, depart AND arrive set to arrive period of most recent scheduled non-mandatory tour=" + chosenStartPeriod + ", " + chosenEndPeriod + "." );
                                }
                                noWindowCountFirstTemp++;
                                noAltChoice = departureTimeChoiceAvailability.length-1;
                            }
                            else {
                                if ( previouslySelectedDepartPeriod < 0 ) {
                                    chosenStartPeriod = altStarts[noAltChoice-1];
                                    chosenEndPeriod = altEnds[noAltChoice-1];
                                    if( hh.getDebugChoiceModels() )
                                        modelLogger.info( "All alternatives already scheduled, and no non-mandatory tour scheduled yet, depart AND arrive set to last period=" + chosenStartPeriod + ", " + chosenEndPeriod + "." );
                                }
                                else {
                                    chosenStartPeriod = previouslySelectedArrivePeriod;
                                    chosenEndPeriod = previouslySelectedArrivePeriod;
                                    if( hh.getDebugChoiceModels() )
                                        modelLogger.info( "All alternatives already scheduled, depart AND arrive set to arrive period of most recent scheduled non-mandatory tour=" + chosenStartPeriod + ", " + chosenEndPeriod + "." );
                                }
                                noWindowCountLastTemp++;
                                noAltChoice = 1;                            
                                if( hh.getDebugChoiceModels() )
                                    modelLogger.info( "All alternatives already scheduled, depart AND arrive set to work tour arrive period=" + chosenEndPeriod + "." );
                            }

                        	// schedule the chosen alternative
                            person.scheduleWindow(chosenStartPeriod, chosenEndPeriod);
                            t.setTourDepartPeriod( chosenStartPeriod );
                            t.setTourArrivePeriod( chosenEndPeriod );
                            previouslySelectedDepartPeriod = chosenStartPeriod;
                            previouslySelectedArrivePeriod = chosenEndPeriod;

                            
                            if ( runModeChoice ) {
                                
                                if ( hh.getDebugChoiceModels() )
                                    hh.logHouseholdObject( "Pre Non-Mandatory Tour Mode Choice Household " + hh.getHhId() + ", Tour " + tourPurpNum + " of " + person.getListOfIndividualNonMandatoryTours().size(), modelLogger );
                                
                                // set the mode choice attributes needed by @variables in the UEC spreadsheets
                                setModeChoiceDmuAttributes(hh, person, t, chosenStartPeriod, chosenEndPeriod);
                                
                                // use the mcModel object already setup for computing logsums and get
                                // the mode choice, where the selected
                                // worklocation and subzone an departure time and duration are set
                                // for this work tour.
                                int chosenMode = mcModel.getModeChoice(mcDmuObject, t.getTourPrimaryPurpose());
                                t.setTourModeChoice(chosenMode);
                                
                                //set tour taps
                                mcModel.setTourTaps(t, mcDmuObject, chosenMode);

                            }
                            
                        }
                        else {
                            
                            // calculate and store the mode choice logsum for the usual work location for this worker at the various
                            // departure time and duration alternativees
                            setTourModeChoiceLogsumsForDepartureTimeAndDurationAlternatives( t, departureTimeChoiceAvailability );


                            if( hh.getDebugChoiceModels() ){
                                hh.logTourObject( loggingHeader, modelLogger, person, t );
                            }

                            todDmuObject.setOriginZone( t.getTourOrigTaz() );
                            todDmuObject.setDestinationZone(t.getTourDestTaz() );
                            
                            todModels[m].computeUtilities ( todDmuObject, todDmuObject.getIndexValues(), departureTimeChoiceAvailability, tourDepartureTimeChoiceSample );

                            Random hhRandom = hh.getHhRandom();
                            int randomCount = hh.getHhRandomCount();
                            double rn = hhRandom.nextDouble();

                            // if the choice model has at least one available alternative, make choice.
                            if ( todModels[m].getAvailabilityCount() > 0 ) 
                            {
                                chosen = todModels[m].getChoiceResult( rn );

                                // debug output
                                if( hh.getDebugChoiceModels() ){

                                    double[] utilities     = todModels[m].getUtilities();
                                    double[] probabilities = todModels[m].getProbabilities();
                                    boolean[] availabilities = todModels[m].getAvailabilities();

                                    String personTypeString = person.getPersonType();
                                    int personNum = person.getPersonNum();
                                    modelLogger.info("Person num: " + personNum  + ", Person type: " + personTypeString + ", Tour Id: " + t.getTourId() + ", Tour num: " + tourPurpNum + " of " + purposeTourLists[tourPurposeIndex].size() + " " + tourPurposeName + " tours." );
                                    modelLogger.info("Alternative            Availability           Utility       Probability           CumProb");
                                    modelLogger.info("--------------------   ------------    --------------    --------------    --------------");

                                    double cumProb = 0.0;
                                    for(int k=0; k < todModels[m].getNumberOfAlternatives(); k++){
                                        cumProb += probabilities[k];
                                        String altString = String.format( "%-3d out=%-3d, in=%-3d", k+1, altStarts[k], altEnds[k] );
                                        modelLogger.info( String.format( "%-20s%15s%18.6e%18.6e%18.6e", altString, availabilities[k+1], utilities[k], probabilities[k], cumProb ) );
                                    }

                                    modelLogger.info(" ");
                                    String altString = String.format( "%-3d out=%-3d, in=%-3d", chosen, altStarts[chosen-1], altEnds[chosen-1] );
                                    modelLogger.info( String.format("Choice: %s, with rn=%.8f, randomCount=%d", altString, rn, randomCount ) );

                                    modelLogger.info( separator );
                                    modelLogger.info("");
                                    modelLogger.info("");


                                    // write choice model alternative info to debug log file
                                    todModels[m].logAlternativesInfo ( choiceModelDescription, decisionMakerLabel );
                                    todModels[m].logSelectionInfo ( choiceModelDescription, decisionMakerLabel, rn, chosen );

                                    // write UEC calculation results to separate model specific log file
                                    loggingHeader = String.format("%s for %s", choiceModelDescription, decisionMakerLabel );
                                    todModels[m].logUECResults( modelLogger, loggingHeader );
                                    
                                }

                            }
                            else {

                                // since there were no alternatives with valid utility, assuming previous
                                // tour of this type scheduled up to the last period, so no periods left
                                // for this tour.
                                
                                //TODO: do a formal check for this so we can still flag other reasons why there's
                                //      no valid utility for any alternative 
                                chosen = departureTimeChoiceAvailability.length-1;
                                noLaterAlternativeCountTemp++;

                            }

                            // schedule the chosen alternative
                            chosenStartPeriod = altStarts[chosen-1];
                            chosenEndPeriod = altEnds[chosen-1];
                            person.scheduleWindow(chosenStartPeriod, chosenEndPeriod);
                            t.setTourDepartPeriod( chosenStartPeriod );
                            t.setTourArrivePeriod( chosenEndPeriod );
                            previouslySelectedDepartPeriod = chosenStartPeriod;
                            previouslySelectedArrivePeriod = chosenEndPeriod;                           

                            
                            
                            if ( runModeChoice ) {
                                
                                if ( hh.getDebugChoiceModels() )
                                    hh.logHouseholdObject( "Pre Individual Non-Mandatory Tour Mode Choice Household " + hh.getHhId() + ", Tour " + tourPurpNum + " of " + purposeTourLists[tourPurposeIndex].size(), todLogger );
                                
                                // set the mode choice attributes needed by @variables in the UEC spreadsheets
                                setModeChoiceDmuAttributes(hh, person, t, chosenStartPeriod, chosenEndPeriod);
                                
                                // use the mcModel object already setup for computing logsums and get
                                // the mode choice, where the selected
                                // worklocation and subzone an departure time and duration are set
                                // for this work tour.
                                int chosenMode = mcModel.getModeChoice(mcDmuObject, t.getTourPrimaryPurpose());
                                t.setTourModeChoice(chosenMode);
                                
                                //set tour taps
                                mcModel.setTourTaps(t, mcDmuObject, chosenMode);
    
                            }

                        }

                    }
                    catch ( Exception e ) {
                        String errorMessage = String.format( "Exception caught for HHID=%d, personNum=%d, individual non-mandatory Departure time choice, tour ArrayList index=%d.", hh.getHhId(), person.getPersonNum(), tourPurpNum-1 );
                        String decisionMakerLabel = String.format ( "Final Individual Non-Mandatory Departure Time Person Object: HH=%d, PersonNum=%d, PersonType=%s", hh.getHhId(), person.getPersonNum(), person.getPersonType() );
                        hh.logPersonObject( decisionMakerLabel, modelLogger, person );
                        todModels[m].logUECResults( modelLogger, errorMessage );

                        logger.error ( errorMessage, e );
                        throw new RuntimeException();
                    }

                    tourPurpNum++;

                }
                
                if ( hh.getDebugChoiceModels() ) {
                    String decisionMakerLabel = String.format ( "Final Individual Non-Mandatory Departure Time Person Object: HH=%d, PersonNum=%d, PersonType=%s", hh.getHhId(), person.getPersonNum(), person.getPersonType() );
                    hh.logPersonObject( decisionMakerLabel, modelLogger, person );
                }
                
            }
        
        }

        hh.setInmtodRandomCount( hh.getHhRandomCount() );
        
    }


    public void applyJointModel( HouseholdIf hh, boolean runModeChoice ){

        // if no joint non-mandatory tours, nothing to do for this household.
        TourIf[] jointTours = hh.getJointTourArray(); 
        if ( jointTours == null || jointTours.length == 0 )
            return;

        Logger modelLogger = todLogger;
        
        // arrange the joint non-mandatory tours for this househol in an array of ArrayLists by purpose
        getPriorityOrderedTourList( jointTours );


        // process tour lists by priority purpose
        for ( int i=0; i < tourPurposeIndexOrder.length; i++ ) {
            
            int tourPurposeIndex = tourPurposeIndexOrder[i];
            
            // process each individual non-mandatory tour from the list
            int m = -1;
            int tourPurpNum = 1;
            int noWindowCountFirstTemp = 0;
            int noWindowCountLastTemp = 0;
            int noLaterAlternativeCountTemp = 0;
            for ( TourIf t : purposeTourLists[tourPurposeIndex] ) {

                try {

                    // get the choice model for the tour purpose
                    String tourPurposeName = t.getTourPrimaryPurpose();
                    m = todModelIndices[purposeNameIndexMap.get(tourPurposeName)];
                    
                    // write debug header
                    String separator = "";
                    String choiceModelDescription = "" ;
                    String decisionMakerLabel = "";
                    String loggingHeader = "";
                    if( hh.getDebugChoiceModels() ) {

                        String personNumsInJointTour = "Person Nums: [";
                        for (int n : t.getPersonNumArray())
                            personNumsInJointTour += " " + n; 
                        personNumsInJointTour += " ]"; 
                        
                        choiceModelDescription = String.format ( "Joint Non-Mandatory Tour Departure Time Choice Model for: Purpose=%s", tourPurposeName );
                        decisionMakerLabel = String.format ( "HH=%d, tourId=%d, %s, num=%d of %d %s tours", hh.getHhId(), t.getTourId(), personNumsInJointTour, tourPurpNum, purposeTourLists[tourPurposeIndex].size(), tourPurposeName );
                        
                        todModels[m].choiceModelUtilityTraceLoggerHeading( choiceModelDescription, decisionMakerLabel );
                            
                        modelLogger.info(" ");
                        loggingHeader = String.format( "%s for %s", choiceModelDescription, decisionMakerLabel );
                        for (int k=0; k < loggingHeader.length(); k++)
                            separator += "+";
                        modelLogger.info( loggingHeader );
                        modelLogger.info( separator );
                        modelLogger.info( "" );
                        modelLogger.info( "" );
                     
                        
                    }

                    // set the dmu object
                    todDmuObject.setHousehold( hh );
                    todDmuObject.setTour( t );

                    
                    // check for multiple tours for this person, by purpose
                    // set the first or second switch if multiple tours for person, by purpose
                    if ( purposeTourLists[tourPurposeIndex].size() == 1 ) {
                        // not a multiple tour pattern
                        todDmuObject.setFirstTour( 0 );
                        todDmuObject.setSubsequentTour( 0 );
                        todDmuObject.setTourNumber( 1 );
                        todDmuObject.setEndOfPreviousScheduledTour( 0 );
                    }
                    else if ( purposeTourLists[tourPurposeIndex].size() > 1 ) {
                        // Two-plus tour multiple tour pattern
                        if ( tourPurpNum == 1 ) {
                            // first of 2+ tours
                            todDmuObject.setFirstTour( 1 );
                            todDmuObject.setSubsequentTour( 0 );
                            todDmuObject.setTourNumber( tourPurpNum );
                            todDmuObject.setEndOfPreviousScheduledTour( 0 );
                        }
                        else {
                            // 2nd or greater tours
                            todDmuObject.setFirstTour( 0 );
                            todDmuObject.setSubsequentTour( 1 );
                            todDmuObject.setTourNumber( tourPurpNum );
                            // the ArrayList is 0-based, and we want the previous tour, so subtract 2 from tourPurpNum to get the right index
                            int otherTourEndHour = purposeTourLists[tourPurposeIndex].get( tourPurpNum-2 ).getTourArrivePeriod();
                            todDmuObject.setEndOfPreviousScheduledTour( otherTourEndHour );
                        }
                    }

                    

                    // set the choice availability and sample                    
                    boolean[] departureTimeChoiceAvailability = hh.getAvailableJointTourTimeWindows(t, altStarts, altEnds);
                    Arrays.fill(tourDepartureTimeChoiceSample, 1);
                    
                    
                    if ( departureTimeChoiceAvailability.length != tourDepartureTimeChoiceSample.length ) {
                        logger.error( String.format( "error in joint non-mandatory departure time choice model for hhId=%d, tour purpose index=%d, tour ArrayList index=%d.", hh.getHhId(), tourPurposeIndex, tourPurpNum-1 ));
                        logger.error( String.format( "length of the availability array determined by the number of alternatives set in the person schedules=%d", departureTimeChoiceAvailability.length ));
                        logger.error( String.format( "does not equal the length of the sample array determined by the number of alternatives in the joint non-mandatory tour UEC=%d.", tourDepartureTimeChoiceSample.length ));
                        throw new RuntimeException();
                    }

                    
                    // if all time windows for this person have already been scheduled, choose either the first and last
                    // alternatives and keep track of the number of times this condition occurs.
                    int alternativeAvailable = -1;
                    for (int a=0; a < departureTimeChoiceAvailability.length; a++) {
                        if ( departureTimeChoiceAvailability[a] ) {
                            alternativeAvailable = a;
                            break;
                        }
                    }

                    int chosen = -1;
                    int chosenStartPeriod = -1;
                    int chosenEndPeriod = -1;
                    

                    // alternate making the first and last periods chosen if no alternatives are available
                    if ( alternativeAvailable < 0 ) {

                        if ( noAltChoice == 1 ) {
                            chosen = 1;
                            noWindowCountFirstTemp++;
                            noAltChoice = departureTimeChoiceAvailability.length-1;
                        }
                        else {
                            chosen = departureTimeChoiceAvailability.length-1;
                            noWindowCountLastTemp++;
                            noAltChoice = 1;                            
                        }

                        // schedule the chosen alternative
                        chosenStartPeriod = altStarts[chosen-1];
                        chosenEndPeriod = altEnds[chosen-1];
                        hh.scheduleJointTourTimeWindows(t, chosenStartPeriod, chosenEndPeriod);

                        t.setTourDepartPeriod( chosenStartPeriod );
                        t.setTourArrivePeriod( chosenEndPeriod );

                        if( hh.getDebugChoiceModels() ){
                            modelLogger.info( "All alternatives already scheduled, chosen set to alt=" + chosen + "." );
                        }
                        
                    }
                    else {
                        
                        // calculate and store the mode choice logsum for the usual work location for this worker at the various
                        // departure time and duration alternativees
                        setTourModeChoiceLogsumsForDepartureTimeAndDurationAlternatives( t, departureTimeChoiceAvailability );


                        if( hh.getDebugChoiceModels() ){
                            for ( int p=1; p < hh.getPersons().length; p++ ){
                                PersonIf pers = hh.getPersons()[p];
                                hh.logTourObject( loggingHeader, modelLogger, pers, t );
                            }
                        }

                        todDmuObject.setOriginZone( t.getTourOrigTaz() );
                        todDmuObject.setDestinationZone(t.getTourDestTaz() );
                        
                        todModels[m].computeUtilities ( todDmuObject, todDmuObject.getIndexValues(), departureTimeChoiceAvailability, tourDepartureTimeChoiceSample );


                        Random hhRandom = hh.getHhRandom();
                        int randomCount = hh.getHhRandomCount();
                        double rn = hhRandom.nextDouble();

                        // if the choice model has at least one available alternative, make choice.
                        if ( todModels[m].getAvailabilityCount() > 0 ) 
                        {
                            chosen = todModels[m].getChoiceResult( rn );

                            // debug output
                            if( hh.getDebugChoiceModels() ){

                                double[] utilities     = todModels[m].getUtilities();
                                double[] probabilities = todModels[m].getProbabilities();
                                boolean[] availabilities = todModels[m].getAvailabilities();

                                modelLogger.info("Tour Id: " + t.getTourId() + ", Tour num: " + tourPurpNum + " of " + purposeTourLists[tourPurposeIndex].size() + " " + tourPurposeName + " tours." );
                                modelLogger.info("Alternative            Availability           Utility       Probability           CumProb");
                                modelLogger.info("--------------------   ------------    --------------    --------------    --------------");

                                double cumProb = 0.0;
                                for(int k=0; k < todModels[m].getNumberOfAlternatives(); k++){
                                    cumProb += probabilities[k];
                                    String altString = String.format( "%-3d out=%-3d, in=%-3d", k+1, altStarts[k], altEnds[k] );
                                    modelLogger.info( String.format( "%-20s%15s%18.6e%18.6e%18.6e", altString, availabilities[k+1], utilities[k], probabilities[k], cumProb ) );
                                }

                                modelLogger.info(" ");
                                String altString = String.format( "%-3d out=%-3d, in=%-3d", chosen, altStarts[chosen-1], altEnds[chosen-1] );
                                modelLogger.info( String.format("Choice: %s, with rn=%.8f, randomCount=%d", altString, rn, randomCount ) );

                                modelLogger.info( separator );
                                modelLogger.info("");
                                modelLogger.info("");


                                // write choice model alternative info to debug log file
                                todModels[m].logAlternativesInfo ( choiceModelDescription, decisionMakerLabel );
                                todModels[m].logSelectionInfo ( choiceModelDescription, decisionMakerLabel, rn, chosen );

                                // write UEC calculation results to separate model specific log file
                                loggingHeader = String.format("%s for %s", choiceModelDescription, decisionMakerLabel );
                                todModels[m].logUECResults( modelLogger, loggingHeader );
                                
                            }

                        }
                        else {

                            // since there were no alternatives with valid utility, assuming previous
                            // tour of this type scheduled up to the last period, so no periods left
                            // for this tour.
                            
                            //TODO: do a formal check for this so we can still flag other reasons why there's
                            //      no valid utility for any alternative 
                            chosen = departureTimeChoiceAvailability.length-1;
                            noLaterAlternativeCountTemp++;

                        }

                        // schedule the chosen alternative
                        chosenStartPeriod = altStarts[chosen-1];
                        chosenEndPeriod = altEnds[chosen-1];
                        hh.scheduleJointTourTimeWindows(t, chosenStartPeriod, chosenEndPeriod);
                        t.setTourDepartPeriod( chosenStartPeriod );
                        t.setTourArrivePeriod( chosenEndPeriod );
                                                   

                        
                        
                        if ( runModeChoice ) {
                            
                            if ( hh.getDebugChoiceModels() )
                                hh.logHouseholdObject( "Pre Individual Non-Mandatory Tour Mode Choice Household " + hh.getHhId() + ", Tour " + tourPurpNum + " of " + purposeTourLists[tourPurposeIndex].size(), todLogger );
                            
                            // set the mode choice attributes needed by @variables in the UEC spreadsheets
                            setModeChoiceDmuAttributes(hh, null, t, chosenStartPeriod, chosenEndPeriod);
                            
                            // use the mcModel object already setup for computing logsums and get
                            // the mode choice, where the selected
                            // worklocation and subzone an departure time and duration are set
                            // for this work tour.
                            int chosenMode = mcModel.getModeChoice(mcDmuObject, t.getTourPrimaryPurpose());
                            t.setTourModeChoice(chosenMode);
                            
                            //set tour taps
                            mcModel.setTourTaps(t, mcDmuObject, chosenMode);

                        }

                    }

                }
                catch ( Exception e ) {
                    String errorMessage = String.format( "Exception caught for HHID=%d, joint non-mandatory Departure time choice, tour ArrayList index=%d.", hh.getHhId(), tourPurpNum-1 );
                    String decisionMakerLabel = "Final Joint Non-Mandatory Departure Time Person Objects:";
                    for ( int p=1; p < hh.getPersons().length; p++ ){
                        PersonIf pers = hh.getPersons()[p];
                        hh.logPersonObject( decisionMakerLabel, modelLogger, pers );
                        todModels[m].logUECResults( modelLogger, errorMessage );
                    }

                    logger.error ( errorMessage, e );
                    throw new RuntimeException();
                }

                tourPurpNum++;

            }
            
            if ( hh.getDebugChoiceModels() ) {
                for ( int p=1; p < hh.getPersons().length; p++ ){
                    PersonIf pers = hh.getPersons()[p];
                    String decisionMakerLabel = String.format ( "Final Joint Non-Mandatory Departure Time Person Objects: HH=%d, PersonNum=%d, PersonType=%s", hh.getHhId(), pers.getPersonNum(), pers.getPersonType() );
                    hh.logPersonObject( decisionMakerLabel, modelLogger, pers );
                }
            }
            
        }
                    

        hh.setJtodRandomCount( hh.getHhRandomCount() );
        
    }


    private void setModeChoiceDmuAttributes(HouseholdIf household, PersonIf person, TourIf t, int startPeriod, int endPeriod)
    {

        t.setTourDepartPeriod(startPeriod);
        t.setTourArrivePeriod(endPeriod);

        // update the MC dmuObjects for this person
        mcDmuObject.setHouseholdObject(household);
        mcDmuObject.setPersonObject(person);
        mcDmuObject.setTourObject(t);
        mcDmuObject.setDmuIndexValues(household.getHhId(), t.getTourOrigTaz(), t.getTourOrigTaz(), t.getTourDestTaz(), household.getDebugChoiceModels());
        
        //create TVPB for tour mode choice model
        mcModel.setTVPBValues(mcDmuObject, t.getTourPrimaryPurpose(), false, household.getDebugChoiceModels());
        
    }
    
    
    private void setTourModeChoiceLogsumsForDepartureTimeAndDurationAlternatives( TourIf tour, boolean[] altAvailable ) {

        PersonIf person = tour.getPersonObject();
        HouseholdIf household = person.getHouseholdObject();

        Arrays.fill(needToComputeLogsum, true);
        Arrays.fill(modeChoiceLogsums, -999);

        Logger modelLogger = todLogger;
        String choiceModelDescription = String.format( "NonMandatory Tour Mode Choice Logsum calculation for %s Departure Time Choice", tour.getTourPrimaryPurpose());
        String decisionMakerLabel = String.format( "HH=%d, PersonNum=%d, PersonType=%s, tourId=%d of %d", household.getHhId(), person.getPersonNum(), person.getPersonType(), tour.getTourId(), person.getListOfWorkTours().size());
        String loggingHeader = String.format("%s    %s", choiceModelDescription, decisionMakerLabel);

        
        for (int a = 1; a <= altStarts.length; a++)
        {

            // if the depart/arrive alternative is unavailable, no need to check to see if a logsum has been calculated
            if ( ! altAvailable[a] )
                continue;
            
            int startPeriod = altStarts[a - 1];
            int endPeriod = altEnds[a - 1];

            int index = modelStructure.getPeriodCombinationIndex(startPeriod, endPeriod);
            if (needToComputeLogsum[index])
            {

                String periodString = modelStructure.getPeriodLabel( modelStructure.getPeriodIndex(startPeriod)) + " to " + modelStructure.getPeriodLabel( modelStructure.getPeriodIndex(endPeriod));

                // set the mode choice attributes needed by @variables in the UEC spreadsheets
                setModeChoiceDmuAttributes(household, person, tour, startPeriod, endPeriod);

                if (household.getDebugChoiceModels())
                    household.logTourObject(loggingHeader + ", " + periodString, modelLogger,
                            person, mcDmuObject.getTourObject());

                try
                {
                    modeChoiceLogsums[index] = mcModel.getModeChoiceLogsum(mcDmuObject, tour.getTourPrimaryPurpose(),
                            modelLogger, choiceModelDescription, decisionMakerLabel + ", " + periodString);
                } catch(Exception e)
                {
                    logger.fatal( "exception caught applying mcModel.getModeChoiceLogsum() for " + periodString + " work tour." );
                    logger.fatal( "choiceModelDescription = " + choiceModelDescription );
                    logger.fatal( "decisionMakerLabel = " + decisionMakerLabel );
                    throw new RuntimeException(e);
                }
                needToComputeLogsum[index] = false;
            }

        }

        todDmuObject.setModeChoiceLogsums(modeChoiceLogsums);

        mcDmuObject.getTourObject().setTourDepartPeriod(0);
        mcDmuObject.getTourObject().setTourArrivePeriod(0);
    }


    /**
     * takes an ArrayList of individual non-mandatory tours
     * creates an array of ArrayLists of tours by purpose
     */
     private void getPriorityOrderedTourList( ArrayList<TourIf> toursIn ){

         // clear the ArrayLists
         for ( int i=0; i < purposeTourLists.length; i++ ){
             if ( purposeTourLists[i] != null )
                 purposeTourLists[i].clear();
         }
         
         // go through the list of non-mandatory tours, and put each into an array of ArrayLists, by purpose.
         for ( TourIf tour : toursIn ) {
             int purposeIndex = tour.getTourPrimaryPurposeIndex();
             purposeTourLists[purposeIndex].add(tour);
         }

    }
     
     /**
     * takes an array of joint non-mandatory tours
     * creates an array of ArrayLists of tours by purpose
      */
      private void getPriorityOrderedTourList( TourIf[] toursIn ){

          // clear the ArrayLists
          for ( int i=0; i < purposeTourLists.length; i++ ){
              if ( purposeTourLists[i] != null )
                  purposeTourLists[i].clear();
          }
          
          // go through the list of non-mandatory tours, and put each into an array of ArrayLists, by purpose.
          for ( TourIf tour : toursIn ) {
              int purposeIndex = tour.getTourPrimaryPurposeIndex();
              purposeTourLists[purposeIndex].add(tour);
          }

     }
      
}