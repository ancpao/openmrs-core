package org.openmrs.api.impl;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openmrs.Concept;
import org.openmrs.Field;
import org.openmrs.FieldAnswer;
import org.openmrs.FieldType;
import org.openmrs.Form;
import org.openmrs.FormField;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.FormService;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.FormDAO;
import org.openmrs.util.OpenmrsConstants;

/**
 * Form-related services
 * 
 * @author Ben Wolfe
 * @author Burke Mamlin
 * @version 1.0
 */
public class FormServiceImpl implements FormService {
	
	//private Log log = LogFactory.getLog(this.getClass());
	
	private FormDAO dao;
	
	public FormServiceImpl() { }
	
	/**
	 * Convenience method for retrieving FormDAO
	 * 
	 * @return Context's FormDAO
	 */
	private FormDAO getFormDAO() {
		//if (!Context.hasPrivilege(OpenmrsConstants.PRIV_VIEW_FORMS))
		//	throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_VIEW_FORMS);
		return dao;
	}
	
	public void setFormDAO(FormDAO dao) {
		this.dao = dao;
	}
	
	/****************************************************************
	 * DAO Methods
	 ****************************************************************/
	
	/**
	 * Create a new form
	 * @param form
	 * @throws APIException
	 */
	public Form createForm(Form form) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_ADD_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_ADD_FORMS);
		
		updateFormProperties(form);
		
		return getFormDAO().createForm(form);
	}

	/**
	 * Get form by internal form identifier
	 * @param formId internal identifier
	 * @return requested form
	 * @throws APIException
	 */
	public Form getForm(Integer formId) throws APIException {
		return getFormDAO().getForm(formId);
	}
	
	/**
	 * @see org.openmrs.api.FormService#getForms(boolean, boolean)
	 */
	public List<Form> getForms(boolean publishedOnly) throws APIException {
		return getForms(publishedOnly, false);
	}
	
	/**
	 * @see org.openmrs.api.FormService#getForms(boolean, boolean)
	 */
	public List<Form> getForms(boolean publishedOnly, boolean includeRetired) throws APIException {
		return getFormDAO().getForms(publishedOnly, includeRetired);
	}
	
	/**
	 * Save changes to form
	 * @param form
	 * @throws APIException
	 */
	public void updateForm(Form form) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		
		updateFormProperties(form);
		
		if (form.isRetired() && form.getRetiredBy() == null) {
			retireForm(form, form.getRetiredReason());
		}
		else if (!form.isRetired() && form.getRetiredBy() != null) {
			unretireForm(form);
		}
		else {
			getFormDAO().updateForm(form);
		}
	}
	
	/**
	 * Set the change time and (conditionally) creation time attributes
	 * @param form
	 */
	private void updateFormProperties(Form form) {
		if (form.getCreator() == null) {
			form.setCreator(Context.getAuthenticatedUser());
			form.setDateCreated(new Date());
		}
		else {
			form.setChangedBy(Context.getAuthenticatedUser());
			form.setDateChanged(new Date());
		}
		
		if (form.getFormFields() != null) {
			for (FormField formField : form.getFormFields()) {
				updateFormFieldProperties(formField);
			}
		}
	}
	
	/**
	 * Duplicate this form and form_fields associated with this form
	 * 
	 * @param form
	 * @return New duplicated form
	 * @throws APIException
	 */
	public Form duplicateForm(Form form) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_ADD_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_ADD_FORMS);
		
		// Map of /Old FormFieldId/ to /New FormField Object/
		//TreeMap<Integer, FormField> formFieldMap = new TreeMap<Integer, FormField>();
		//formFieldMap.put(null, null); //for parentless formFields

		for (FormField formField : form.getFormFields()) {
			//formFieldMap.put(formField.getFormFieldId(), formField);
			formField.setFormFieldId(null);
			//formField.setParent(formFieldMap.get(formField.getParent().getFormFieldId()));
		}
		// this is required because Hibernate would recognize the original collection
		form.setFormFields(new HashSet<FormField>(form.getFormFields()));

		form.setFormId(null);
		
		Context.clearSession();
		
		Form newForm = getFormDAO().duplicateForm(form);
		
		return newForm;
	}

	/** 
	 * Mark form as voided (effectively deleting form without removing
	 * their data &mdash; since anything the form touched in the database
	 * will still have their internal identifier and point to the voided
	 * form for historical tracking purposes.
	 * 
	 * @param form
	 * @param reason
	 * @throws APIException
	 */
	public void retireForm(Form form, String reason) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		form.setRetired(true);
		form.setRetiredBy(Context.getAuthenticatedUser());
		form.setDateRetired(new Date());
		form.setRetiredReason(reason);
		updateForm(form);
	}
	
	/**
	 * Clear voided flag for form (equivalent to an "undelete" or
	 * Lazarus Effect for form)
	 * 
	 * @param form
	 * @throws APIException
	 */
	public void unretireForm(Form form) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		form.setRetired(false);
		form.setRetiredBy(null);
		form.setDateRetired(null);
		form.setRetiredReason("");
		updateForm(form);
	}
	
	/**
	 * Delete form from database. This is included for troubleshooting and
	 * low-level system administration. Ideally, this method should <b>never</b>
	 * be called &mdash; <code>Forms</code> should be <em>retired</em> and
	 * not <em>deleted</em> altogether (since many foreign key constraints
	 * depend on forms, deleting a form would require deleting all traces, and
	 * any historical trail would be lost).
	 * 
	 * This method only clears form roles and attempts to delete the form
	 * record. If the form has been included in any other parts of the database
	 * (through a foreign key), the attempt to delete the form will violate
	 * foreign key constraints and fail.
	 * 
	 * @param form
	 * @throws APIException
	 */
	public void deleteForm(Form form) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_DELETE_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_DELETE_FORMS);
		getFormDAO().deleteForm(form);
	}
	
	/**
	 * Get all field types
	 * 
	 * @return field types list
	 * @throws APIException
	 */
	public List<FieldType> getFieldTypes() throws APIException {
		return getFormDAO().getFieldTypes();
	}

	/**
	 * Get fieldType by internal identifier
	 * 
	 * @param fieldType id
	 * @return fieldType with given internal identifier
	 * @throws APIException
	 */
	public FieldType getFieldType(Integer fieldTypeId) throws APIException {
		return getFormDAO().getFieldType(fieldTypeId);
	}
	
	/**
	 * 
	 * @return list of forms in the db
	 * @throws APIException
	 */
	public List<Form> getForms() throws APIException {
		return getFormDAO().getForms();
	}
	
	/**
	 * Returns the forms with which this concept is associated
	 * @return
	 * @throws APIException
	 */
	public Set<Form> getForms(Concept c) throws APIException {
		Set<Form> forms = new HashSet<Form>();
		forms.addAll(getFormDAO().getForms(c));
		return forms;
	}

	/**
	 * @param form
	 * @return list of fields for a specific form
	 * @throws APIException
	 */
	public List<FormField> getFormFields(Form form) throws APIException {
		return getFormDAO().getFormFields(form);
	}
	
	/**
	 * 
	 * @return list of fields in the db matching part of search term
	 * @throws APIException
	 */
	public List<Field> findFields(String searchPhrase) throws APIException {
		return getFormDAO().findFields(searchPhrase);
	}
	
	/**
	 * 
	 * @return list of fields in the db for given concept
	 * @throws APIException
	 */
	public List<Field> findFields(Concept concept) throws APIException {
		return getFormDAO().findFields(concept);
	}
	
	
	/**
	 * 
	 * @return list of fields in the db
	 * @throws APIException
	 */
	public List<Field> getFields() throws APIException {
		return getFormDAO().getFields();
	}
	
	/**
	 * 
	 * @param fieldId
	 * @return
	 * @throws APIException
	 */
	public Field getField(Integer fieldId) throws APIException {
		return getFormDAO().getField(fieldId);
	}

	
	/**
	 * 
	 * @param field
	 * @throws APIException
	 */
	public void createField(Field field) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		updateFieldProperties(field);
		getFormDAO().createField(field);
	}

	/**
	 * 
	 * @param field
	 * @throws APIException
	 */
	public void updateField(Field field) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		updateFieldProperties(field);
		getFormDAO().updateField(field);
	}
	
	/**
	 * Set the change time and (conditionally) creation time attributes
	 * @param form
	 */
	private void updateFieldProperties(Field field) {
		if (field.getCreator() == null) {
			field.setCreator(Context.getAuthenticatedUser());
			field.setDateCreated(new Date());
		}
		else {
			field.setChangedBy(Context.getAuthenticatedUser());
			field.setDateChanged(new Date());
		}
	}
	
	/**
	 * 
	 * @param field
	 * @throws APIException
	 */
	public void deleteField(Field field) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		getFormDAO().deleteField(field);
	}
	
	/**
	 * 
	 * @param fieldId
	 * @return
	 * @throws APIException
	 */
	public FormField getFormField(Integer formFieldId) throws APIException {
		return getFormDAO().getFormField(formFieldId);
	}
	
	/**
	 * Finds the FormField defined for this form/concept combination 
	 * 
	 * @param form
	 * @param concept
	 * @return Formfield for this concept
	 * @throws APIException
	 */
	public FormField getFormField(Form form, Concept concept) throws APIException {
		return getFormDAO().getFormField(form, concept);
	}
	
	/**
	 * 
	 * @param formField
	 * @throws APIException
	 */
	public void createFormField(FormField formField) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		
		updateFormFieldProperties(formField);
		
		getFormDAO().createFormField(formField);
	}
	
	/**
	 * 
	 * @param formField
	 * @throws APIException
	 */
	public void updateFormField(FormField formField) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		
		updateFormFieldProperties(formField);
		
		getFormDAO().updateFormField(formField);
	}
	
	/**
	 * Set the change time and (conditionally) creation time attributes for all
	 * of this form's formfields and for fields of those formfields
	 * @param form Form to update FormFields for
	 */
	private void updateFormFieldProperties(FormField formField) {
		if (formField.getCreator() == null) {
			formField.setCreator(Context.getAuthenticatedUser());
			formField.setDateCreated(new Date());
		}
		else {
			formField.setChangedBy(Context.getAuthenticatedUser());
			formField.setDateChanged(new Date());
		}
		
		Field field = formField.getField();
		if (field.getCreator() == null) {
			field.setCreator(Context.getAuthenticatedUser());
			field.setDateCreated(new Date());
			// don't change the changed by and date changed for 
			// form field updates
		}		
	}
	
	/**
	 * @see org.openmrs.api.FormService#deleteFormField(org.openmrs.FormField)
	 */
	public void deleteFormField(FormField formField) throws APIException {
		if (!Context.hasPrivilege(OpenmrsConstants.PRIV_EDIT_FORMS))
			throw new APIAuthenticationException("Privilege required: " + OpenmrsConstants.PRIV_EDIT_FORMS);
		getFormDAO().deleteFormField(formField);
	}

	/**
     * @see org.openmrs.api.FormService#getFieldByGuid(java.lang.String)
     */
    public Field getFieldByGuid(String guid) throws APIException {
		return getFormDAO().getFieldByGuid(guid);
    }

    public FieldAnswer getFieldAnswerByGuid(String guid) throws APIException {
		return getFormDAO().getFieldAnswerByGuid(guid);
    }

	/**
     * @see org.openmrs.api.FormService#getFieldTypeByGuid(java.lang.String)
     */
    public FieldType getFieldTypeByGuid(String guid) throws APIException {
		return getFormDAO().getFieldTypeByGuid(guid);
    }

	/**
     * @see org.openmrs.api.FormService#getFormByGuid(java.lang.String)
     */
    public Form getFormByGuid(String guid) throws APIException {
		return getFormDAO().getFormByGuid(guid);
    }

	/**
     * @see org.openmrs.api.FormService#getFormFieldByGuid(java.lang.String)
     */
    public FormField getFormFieldByGuid(String guid) throws APIException {
		return getFormDAO().getFormFieldByGuid(guid);
    }
	
	/**
     * @see org.openmrs.api.FormService#findForms(java.lang.String, boolean, boolean)
     */
    public List<Form> findForms(String text, boolean includeUnpublished, boolean includeRetired) {
	   return getFormDAO().findForms(text, includeUnpublished, includeRetired);
    }
    
}