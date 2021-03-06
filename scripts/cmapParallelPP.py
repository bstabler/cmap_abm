#############################################################################
# Shortest Path code for CMAP non-motorized modes
# Roshan Kumar, kumarr1@pbworld.com, 12/20/2012
#############################################################################

#Should always be placed in the same folder as SPwrapper.py

#The multiple CSVs generated by the SP script are conflated into one CSV

import parameters
import os

#############################################################################

def parallelPostProcess():
    try:
        f = open(parameters.outputSPFile, "w")
        #f.write('"Origin","Destin","SP","Euclid","Ratio","nearHwy","nearHwyDist","LU_1","LU_2","LU_3","LU_4","LU_5","LU_6","LU_7","LU_8","LU_9","LU_10","LU_11","LU_12"\n')
        f.write('"Origin","Destin","SP","Euclid","Ratio"\n')

        processNo = 1
        while processNo<=parameters.number_of_cores:
            fileNo = "%s_" % (processNo)
            fileN = fileNo+"_test_parallel_SP.txt"
            #print fileN
            inputFile = os.path.join(parameters.root, parameters.int_output, fileN)
            #print inputFile
            with open(inputFile, "r") as file1:
                old = file1.read()
                f.write(old)
            processNo = processNo+1
        f.close()
    except IOError:
        pass
