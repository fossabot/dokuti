/****************************************************
* Copyright (c) 2019, Grindrod Bank Limited
* License MIT: https://opensource.org/licenses/MIT
****************************************************/
package za.co.grindrodbank.dokuti.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.openapitools.api.DocumentsApi;
import org.openapitools.model.CreateDocumentResponse;
import org.openapitools.model.Document;
import org.openapitools.model.DocumentAttribute;
import org.openapitools.model.DocumentAttributeRequest;
import org.openapitools.model.DocumentTagList;
import org.openapitools.model.DocumentVersion;
import org.openapitools.model.Group;
import org.openapitools.model.LookupTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import za.co.grindrodbank.dokuti.attribute.AttributeService;
import za.co.grindrodbank.dokuti.documentattribute.DocumentAttributeEntity;
import za.co.grindrodbank.dokuti.documenttag.DocumentTagService;
import za.co.grindrodbank.dokuti.documentversion.DocumentVersionEntity;
import za.co.grindrodbank.dokuti.documentversion.DocumentVersionService;
import za.co.grindrodbank.dokuti.events.PaginatedResultsRetrievedEvent;
import za.co.grindrodbank.dokuti.group.GroupEntity;
import za.co.grindrodbank.dokuti.group.GroupService;
import za.co.grindrodbank.dokuti.service.databaseentitytoapidatatransferobjectmapper.DatabaseEntityToApiDataTransferObjectMapperService;
import za.co.grindrodbank.dokuti.utilities.ParseOrderByQueryParam;

@RestController
@PreAuthorize("hasRole('DOKUTI_USER') or hasRole('DOKUTI_ADMIN')")
public class DocumentControllerImpl implements DocumentsApi {
	@Autowired
	private DocumentService documentService;
	@Autowired
	private ApplicationEventPublisher eventPublisher;
	@Autowired
	private GroupService groupService;
	@Autowired
	private AttributeService attributeService;
	@Autowired
	private DocumentVersionService documentVersionService;
	@Autowired
	private DocumentTagService documentTagService;
	@Autowired
	private DatabaseEntityToApiDataTransferObjectMapperService databaseEntityToApiDataTranfserObjectMapperService;

	Logger logger = LoggerFactory.getLogger(DocumentControllerImpl.class);

	private static final String DEFAULT_SORT_FIELD = "name";

	@Override
	public ResponseEntity<CreateDocumentResponse> addDocument(String description, MultipartFile file) {
		DocumentEntity documentEntity = documentService.createNewDocument(file, description);
		CreateDocumentResponse document = new CreateDocumentResponse();
		BeanUtils.copyProperties(documentEntity, document);

		return new ResponseEntity<>(document, HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Document> getDocument(UUID documentId) {
		DocumentEntity documentEntity = documentService.findById(documentId);

		return new ResponseEntity<>(
				databaseEntityToApiDataTranfserObjectMapperService.mapDocumentEntityToDocument(documentEntity),
				HttpStatus.OK);
	}

	@Override
	public ResponseEntity<List<Document>> getDocuments(Integer page, Integer size, String filterName,
			List<String> filterGroupNames, List<String> filterTags, List<String> filterAttributes,
			List<String> orderBy) {
		Sort sort = ParseOrderByQueryParam.resolveArgument(orderBy, DEFAULT_SORT_FIELD);
		final PageRequest pageRequest = PageRequest.of(page, size, sort);
		Page<DocumentEntity> documentEntities = documentService.findAll(pageRequest, filterName, filterTags,
				filterAttributes, filterGroupNames);

		if (documentEntities.hasContent()) {
			Page<Document> documents = databaseEntityToApiDataTranfserObjectMapperService
					.mapDocumentEntityPageToDocumentPage(documentEntities);
			HttpHeaders headers = new HttpHeaders();
			eventPublisher.publishEvent(new PaginatedResultsRetrievedEvent<Document>(this, documents, headers));

			return new ResponseEntity<>(documents.getContent(), headers, HttpStatus.OK);
		}

		return new ResponseEntity<>(new ArrayList<>(), HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Group> createDocumentGroup(UUID documentId, UUID groupId) {
		DocumentEntity document = documentService.findById(documentId);
		GroupEntity groupEntity = groupService.findById(groupId);
		documentService.addDocumentToGroup(document, groupEntity);

		return new ResponseEntity<>(
				databaseEntityToApiDataTranfserObjectMapperService.mapGroupEntityToGroup(groupEntity), HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Void> deleteDocumentGroup(UUID documentId, UUID groupId) {
		DocumentEntity documentEntity = documentService.findById(documentId);
		GroupEntity groupEntity = groupService.findById(groupId);
		documentService.removeDocumentFromGroup(documentEntity, groupEntity);
		// Many REST guidelines indicate that it is good practice to return 204 with no
		// response entity if the record is successfully deleted.
		return new ResponseEntity<>(null, HttpStatus.NO_CONTENT);
	}

	@Override
	public ResponseEntity<DocumentAttribute> createDocumentAttribute(UUID documentId, Integer attributeId,
			DocumentAttributeRequest documentAttributeRequest) {
		DocumentAttribute documentAttribute = createOrUpdateDocumentAttribute(documentId, attributeId,
				documentAttributeRequest);

		return new ResponseEntity<>(documentAttribute, HttpStatus.OK);
	}

	private DocumentAttribute createOrUpdateDocumentAttribute(UUID documentId, Integer attributeId,
			DocumentAttributeRequest documentAttributeRequest) {
		DocumentAttributeEntity documentAttributeEntity = documentService.addDocumentAttribute(
				documentService.findById(documentId), attributeService.findById(attributeId.shortValue()),
				documentAttributeRequest.getValue());

		return databaseEntityToApiDataTranfserObjectMapperService
				.mapDocumentAttributeEntityToDocumentAttribute(documentAttributeEntity);
	}

	@Override
	public ResponseEntity<DocumentAttribute> updateDocumentAttribute(UUID documentId, Integer attributeId,
			DocumentAttributeRequest documentAttributeRequest) {

		return new ResponseEntity<>(createOrUpdateDocumentAttribute(documentId, attributeId, documentAttributeRequest),
				HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Void> deleteDocumentAttribute(UUID documentId, Integer attributeId) {
		documentService.removeDocumentAttribute(documentService.findById(documentId),
				attributeService.findById(attributeId.shortValue()));

		return new ResponseEntity<>(null, HttpStatus.NO_CONTENT);
	}

	@Override
	public ResponseEntity<Resource> downloadDocument(UUID documentId) {
		DocumentEntity documentEntity = documentService.findById(documentId);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + documentEntity.getName() + "\"")
				.contentType(MediaType.parseMediaType(documentEntity.getContentType()))
				.body(documentService.getLatestDocumentData(documentEntity));
	}

	@Override
	public ResponseEntity<DocumentVersion> updateDocument(UUID documentId, MultipartFile file, String description) {
		DocumentEntity documentEntity = documentService.findById(documentId);

		if (description != null) {
			documentEntity.setDescription(description);
		}

		documentEntity = documentService.updateDocument(Optional.of(file), documentEntity);
		DocumentVersion documentVersion = new DocumentVersion();
		documentVersion.setChecksum(documentEntity.getLatestDocumentVersion().getChecksum());
		documentVersion
				.setCreatedDateTime(documentEntity.getLatestDocumentVersion().getCreatedDateTime().toOffsetDateTime());
		documentVersion.setId(documentEntity.getLatestDocumentVersion().getId());

		return new ResponseEntity<>(documentVersion, HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Resource> downloadDocumentVersion(UUID documentId, UUID documentVersionId) {
		DocumentVersionEntity documentVersion = documentVersionService.findById(documentVersionId);

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + documentVersion.getDocument().getName() + "\"")
				.contentType(MediaType.parseMediaType(documentVersion.getDocument().getContentType()))
				.body(documentService.getDocumentDataForVersion(documentVersion.getDocument(), documentVersion));
	}

	@Override
	public ResponseEntity<DocumentTagList> addDocumentTags(UUID documentId, List<LookupTag> lookupTag) {
		DocumentEntity documentEntity = documentService.findById(documentId);

		List<String> tags = new ArrayList<>();
		lookupTag.forEach(lookupTagElement -> {
			tags.add(lookupTagElement.getTag());
		});

		documentTagService.createDocumentTags(tags, documentEntity);
		DocumentTagList documentTagList = new DocumentTagList();
		documentTagList.setDocumentId(documentId);
		documentTagList.setTags(lookupTag);

		return new ResponseEntity<>(documentTagList, HttpStatus.OK);
	}

	@Override
	public ResponseEntity<Void> removeAllDocumentTags(UUID documentId) {
		documentService.removeAllDocumentTags(documentService.findById(documentId));

		return new ResponseEntity<>(null, HttpStatus.NO_CONTENT);
	}

}
