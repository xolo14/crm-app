package com.syncpedia.crm.ui.fragments

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.BottomSheetContactBinding
import com.syncpedia.crm.databinding.FragmentContactsBinding
import com.syncpedia.crm.models.ContactItem
import com.syncpedia.crm.ui.adapters.ContactAdapter
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.ContactReader
import com.syncpedia.crm.utils.PermissionHelper

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactAdapter
    private var allContacts: List<ContactItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ContactAdapter { contact -> showContactSheet(contact) }
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = adapter

        val searchView = binding.searchView
        val editText = searchView.findViewById<android.widget.EditText>(
            androidx.appcompat.R.id.search_src_text
        )
        editText?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        editText?.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filter(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filter(newText.orEmpty())
                return true
            }
        })

        // The first load runs in onResume.
    }

    private fun loadContacts() {
        if (!PermissionHelper.has(requireContext(), Manifest.permission.READ_CONTACTS)) {
            PermissionHelper.requestAll(requireActivity())
            return
        }
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Large phonebooks take seconds to read; keep it off the UI thread.
            val contacts = withContext(Dispatchers.IO) { ContactReader.getAll(app) }
            if (_binding == null) return@launch
            allContacts = contacts
            filter(binding.searchView.query?.toString().orEmpty())
        }
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allContacts
        else allContacts.filter {
            it.name.lowercase().contains(q) || it.phone.contains(q)
        }
        adapter.submit(filtered)
        updateEmptyState(filtered)
    }

    private fun updateEmptyState(list: List<ContactItem>) {
        if (_binding == null) return
        if (list.isEmpty()) {
            binding.rvContacts.visibility = View.GONE
            binding.layoutEmpty.visibility = View.VISIBLE
        } else {
            binding.rvContacts.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    private fun showContactSheet(contact: ContactItem) {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = BottomSheetContactBinding.inflate(layoutInflater)
        sheet.tvContactName.text = contact.name
        sheet.tvContactPhone.text = contact.phone
        sheet.btnCallContact.setOnClickListener {
            AppDialer.dial(requireContext(), contact.phone, contact.name)
            dialog.dismiss()
        }
        sheet.btnAddToCrm.setOnClickListener {
            dialog.dismiss()
            val activity = activity
            if (activity is com.syncpedia.crm.ui.MainActivity) {
                activity.openLeadsAndAdd(contact.name, contact.phone)
            } else {
                Toast.makeText(requireContext(), "Open Leads tab to add", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        if (allContacts.isEmpty()) loadContacts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
