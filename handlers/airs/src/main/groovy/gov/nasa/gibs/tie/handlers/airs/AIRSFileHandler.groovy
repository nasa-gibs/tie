package gov.nasa.gibs.tie.handlers.airs

import gov.nasa.horizon.common.api.file.FileProduct
import gov.nasa.horizon.common.api.file.LocalFileProduct
import gov.nasa.horizon.common.api.serviceprofile.ServiceProfile
import gov.nasa.horizon.common.api.util.ChecksumUtility
import gov.nasa.horizon.common.httpfetch.api.HttpFileProduct
import gov.nasa.horizon.handlers.framework.DataHandlerException
import gov.nasa.horizon.handlers.framework.FileHandler
import gov.nasa.horizon.handlers.framework.Product
import gov.nasa.horizon.sigevent.api.EventType
import gov.nasa.horizon.sigevent.api.SigEvent
import gov.nasa.gibs.tie.handlers.common.CacheFileInfo
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

/**
 * AIRS file handler class
 *
 * @author T. Huang
 * @version $Id: $
 */
class AIRSFileHandler implements FileHandler {

   private static Log logger = LogFactory.getLog(AIRSFileHandler.class)

   private AIRSProductType productType

   private SigEvent sigEvent

   public AIRSFileHandler(AIRSProductType productType) {
      this.productType = productType
      this.sigEvent = new SigEvent(productType.sigEventURL)
   }

   @Override
   void preprocess() {

   }

   @Override
   void process(List<FileProduct> fps) throws DataHandlerException {

      String productName = fps.find { FileProduct fp ->
         fp.name.endsWith(".png")
      }.name
      productName = productName?.substring(0, productName.lastIndexOf(".png"))

      if (!productName) {
         def fn = fps.collect {
            it.name
         }
         logger.warn("Incomplete product encountered.  Only contains ${fn}")
         return
      }

      Date timetag = new Date()
      String location = "${productType.dataStorage}${File.separator}${productName}_${timetag.time}${File.separator}"
      String shadow = "${productType.dataStorage}${File.separator}.shadow${File.separator}${productName}${File.separator}"

      // download to a shadow directory to prevent partial read by any scanners
      File s = new File(shadow)
      if (!s.exists()) {
         try {
            if (!s.mkdirs()) {
               logger.error("Unable to create shadow directory for download: ${shadow}")
               throw new DataHandlerException("Unable to create shadow directory for download: ${shadow}")
            }
         } catch (SecurityException e) {
            throw new DataHandlerException("Unable to create shadow directory for download: ${shadow}", e)
         }
      }

      Product product = new Product(productType, productName)
      product.shadowLocation = shadow
      product.stageLocation = location
      product.ingestStart = new Date()
      int max_retries = 5

      fps.each { HttpFileProduct fileProduct ->
         int retries = 1
         while (retries <= max_retries) {
            logger.debug("Start to download file ${fileProduct.name}.. attempt ${retries}")
            String shadowLocation = "${shadow}${File.separator}${fileProduct.name}"
            FileOutputStream fos = new FileOutputStream(new File(shadowLocation))
            fileProduct.digestAlgorithm = ChecksumUtility.DigestAlgorithm.MD5
            //if (!alg) {
            //   alg = ChecksumUtility.DigestAlgorithm.MD5
            //}
            String checksum = ChecksumUtility.getDigest(fileProduct.digestAlgorithm, fileProduct.inputStream, fos)
            if (!checksum) {
               if (retries == max_retries) {
                  logger.error("Unable to download file: " + fileProduct.friendlyURI)
                  throw new DataHandlerException("Unable to download file after ${retries} attemps: " + fileProduct.friendlyURI)
               } else {
                  logger.debug("Download unsuccessful.  Handler will retry...")
                  // sleep 1 sec and retry
                  sleep(1000)
               }
            } else {
               File sf = new File(shadowLocation)
               if (!sf.text.contains('PNG')) {
                  logger.debug("No image file found at this time.")
                  sf.delete()
               } else {
                  fileProduct.digestValue = checksum
                  fileProduct.size = sf.size()
                  CacheFileInfo cfi = new CacheFileInfo()
                  cfi.product = productName
                  cfi.name = fileProduct.name
                  cfi.modified = fileProduct.lastModifiedTime
                  cfi.size = fileProduct.size
                  if (productType.isInCache(cfi)) {
                     logger.debug("File already previously downloaded.  Delete and skip it")
                     sf.delete()
                  } else {
                     product.addFileProduct(fileProduct)

                     // download success
                     logger.debug("Retrieved file: ${fileProduct.name}")
                  }
                  break
               }
               ++retries
            }
         }
      }
	  
	  // WMS endpoint doesn't provide actual size of the file and checksum.
	  // At this point the handler ingested all the files and computed the checksum locally.
	  // It is time to compare the locally computed values with previously ingested files
	  // listed in cache
	  def inCache = true
	  for (FileProduct fp in product.files) {
		 logger.debug("Checking file ${fp.name} in cache.")
		 CacheFileInfo cfi = new CacheFileInfo()
		 cfi.product = productName
		 cfi.name = fp.name
		 cfi.modified = fp.lastModifiedTime
		 cfi.size = fp.size
		 cfi.checksumAlgorithm = fp.digestAlgorithm.toString()
		 cfi.checksumValue = fp.digestValue
		 if (!productType.isInCacheIgnoreTimestamp(cfi)) {
			inCache = false
			break
		 }
	  }
	  if (inCache) {
		 logger.debug ("Product ${product.name} was previously ingested.. cleanup duplicate ingested product")
		 for (FileProduct fp in product.files) {
			fp.delete()
		 }
		 s.deleteDir()
		 return // breakout of current closure
	  }

	  
      product.ingestStop = new Date()

      File sh = new File(shadow)
      if (sh.exists() && sh.listFiles().size() == 0) {
         // no file needs to be archived
         sh.deleteDir()
      } else {
         // completed file downloads.  Move the product to a visible location

         // if a previous copy exists, then delete it first and use the latest
         File newLocation = new File(location)
         if (newLocation.exists()) {
            newLocation.listFiles().each {
               it.delete()
            }
            newLocation.deleteDir()
         }

         if (!s.renameTo(newLocation)) {
            logger.error("Unable to move downloaded product to ${location}")
            _cleanup(shadow)
            throw new DataHandlerException("Unable to move downloaded product to ${location}")
         }

         logger.info("Retrieved new product ${productType.name}:${productName}")

		 
		 // Generate geo file.  This is needed for MRF generation
		 String geoName = "${location}${File.separator}${productName}.pgw"
		 File geoFile = new File(geoName)
		 geoFile << "0.250000000000\n0.000000000000\n0.000000000000\n-0.250000000000\n-179.875000000000\n89.875000000000"
		 product.addFileProduct(new LocalFileProduct(geoName))
		 
		 
         ServiceProfile sp = productType.metadataHarvesterFactory.createMetadataHarvester().createServiceProfile(product)

         if (sp != null) {

            // write SIP to pending directory
            String pendingSIP = "${productType.metadataPending}${File.separator}${productName}_${timetag.time}.xml"
            logger.debug("Store SIP in ${pendingSIP}")

            new File(pendingSIP).write(sp.toString())

            // update cache
            for (FileProduct fp in product.files) {
               logger.debug("Checking file ${fp.name} in cache.")
               CacheFileInfo cfi = new CacheFileInfo()
               cfi.product = productName
               cfi.name = fp.name
               cfi.modified = fp.lastModifiedTime
               cfi.size = fp.size
               cfi.checksumAlgorithm = fp.digestAlgorithm.toString()
               cfi.checksumValue = fp.digestValue
               if (productType.updateCache(cfi)) {
                  logger.debug("${productType.name}:${cfi.name} has been added to cache.")
               }
            }

         } else {
            String errorDir = "${productType.validationError}${File.separator}${productName}"
            logger.error("Unable to extract metdata for ${productName}.  Moving product to ${errorDir}")

            new File(location).renameTo(new File(errorDir))

            this.sigEvent.create(EventType.Error, productType.name, 'AIRSDataHandler',
                  'AIRSDataHandler',
                  InetAddress.localHost.hostAddress,
                  "Unable to extract metadata for ${productName}.  Proudct moved to ${errorDir}")

         }
      }

   }

   @Override
   void postprocess() {

   }

   @Override
   void onError(Throwable throwable) {

   }

   protected static void _cleanup(String downloadLocation) {
      File dir = new File(downloadLocation)
      if (dir.exists()) {
         dir.listFiles().each {
            it.delete()
         }
         dir.deleteDir()
      }
   }
}