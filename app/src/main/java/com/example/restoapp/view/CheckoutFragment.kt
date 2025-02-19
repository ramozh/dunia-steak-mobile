package com.example.restoapp.view

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.auth0.android.jwt.JWT
import com.example.restoapp.R
import com.example.restoapp.adapter.CartListAdapter
import com.example.restoapp.databinding.FragmentCheckoutBinding
import com.example.restoapp.global.GlobalData
import com.example.restoapp.model.OrderDetail
import com.example.restoapp.util.convertToRupiah
import com.example.restoapp.util.getAccToken
import com.example.restoapp.util.setNewAccToken
import com.example.restoapp.view.auth.LoginActivity
import com.example.restoapp.viewmodel.OrderViewModel
import com.example.restoapp.viewmodel.TransactionViewModel
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.midtrans.sdk.uikit.api.model.CustomColorTheme
import com.midtrans.sdk.uikit.api.model.TransactionResult
import com.midtrans.sdk.uikit.external.UiKitApi
import com.midtrans.sdk.uikit.internal.util.UiKitConstants
import java.util.Date

class CheckoutFragment : Fragment() {

    private lateinit var binding: FragmentCheckoutBinding
    private lateinit var viewModel: OrderViewModel
    private lateinit var vmTrans: TransactionViewModel
    private lateinit var orderDetailListAdapter:CartListAdapter
    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("result code",result.resultCode.toString())
            if (result.resultCode == AppCompatActivity.RESULT_OK) {
                result.data?.let {
                    val transactionResult = it.getParcelableExtra<TransactionResult>(UiKitConstants.KEY_TRANSACTION_RESULT)
                    Log.d("transaction result", "transaction id : ${transactionResult?.transactionId}")
                    if (transactionResult != null) {
                        Log.d("transactionResult", "not null")
                        when(transactionResult.status){
                            UiKitConstants.STATUS_CANCELED -> {
                                Log.d("transactionResult.status","canceled")
                                binding.buttonContinue.text = "CHECK OUT"
                                enableButton()
                            }
                            else -> {
                                Log.d("transactionResult stats",transactionResult.status)
                                GlobalData.orderDetail.clear()
                                val transactionId = transactionResult.transactionId
                                val action = CheckoutFragmentDirections.actionAfterTransaction(transactionId!!)
                                Navigation.findNavController(requireView()).navigate(action)
                            }
                        }
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCheckoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildUiKit()
        viewModel = ViewModelProvider(this).get(OrderViewModel::class.java)
        vmTrans = ViewModelProvider(this).get(TransactionViewModel::class.java)
        orderDetailListAdapter = CartListAdapter(arrayListOf(), viewModel)

        setGrandtotal(GlobalData.orderDetail)
        binding.recView.layoutManager = LinearLayoutManager(context)
        binding.recView.isNestedScrollingEnabled = false
        binding.recView.adapter = orderDetailListAdapter

        var orderDetails = GlobalData.orderDetail
        Log.d("co frag", orderDetails.toString())
        orderDetailListAdapter.updateOrderDetailList(orderDetails)

        checkOrderDetail(orderDetails)
        var time:String? = null
        var isScheduledOrder = false
        notScheduledOrder()
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
            .setTheme(R.style.BaseTheme_TimePicker)
            .setTitleText("Order for")
            .build()

        timePicker.addOnPositiveButtonClickListener {
            isScheduledOrder = true
            scheduledOrder()
            time = "${timePicker.hour}:${timePicker.minute}"
            binding.textOrderAt.text = time
        }
        timePicker.addOnDismissListener {
            if (!isScheduledOrder){
                binding.chipOrderNow.isChecked = true
                notScheduledOrder()
                time = null
            }
        }
        binding.buttonContinue.setOnClickListener {
            val (accToken) = getAccToken(requireActivity())
            accToken?.let {
                if (it.isNotEmpty()){
                    val expToken = JWT(it).expiresAt
                    if (expToken != null) {
                        Log.d("exp token", expToken.time.toString())
                        Log.d("now", Date().time.toString())
                        //acctoken expired
                        if( Date().time > expToken.time){
                            Log.d("exp token", "token expired")
                            setNewAccToken(requireActivity(),"","")
                            startActivity(Intent(requireContext(), LoginActivity::class.java))
                        }else{
                            Log.d("exp token", "not expired yet")
                            orderDetails = GlobalData.orderDetail
                            Log.d("isScheduledOrder",isScheduledOrder.toString())

                            binding.buttonContinue.text = "Loading..."
                            disableButton()

                            vmTrans.createTransaction(orderDetails,isScheduledOrder,time,requireActivity())

                            vmTrans.snapToken.observe(viewLifecycleOwner) { token ->
                                Log.d("executed","snaptoken : $token")
                                UiKitApi.getDefaultInstance().startPaymentUiFlow(
                                    requireActivity(),
                                    launcher,
                                    token
                                )
                            }
                        }
                    }
                }else{
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                }
            }

        }

        binding.chipOrderNow.setOnClickListener {
            isScheduledOrder = false
            notScheduledOrder()
        }

        binding.chipScheduleOrder.setOnClickListener {
            Log.d("chip schedule order","clicked")
            timePicker.show(parentFragmentManager, "tag")
        }

        observeViewModel()
    }
    private fun enableButton() {
        with(binding){
            buttonContinue.isEnabled = true
            buttonContinue.setTextColor(resources.getColorStateList(R.color.md_theme_secondary))
            buttonContinue.backgroundTintList = resources.getColorStateList(R.color.md_theme_primary)

        }
    }
    private fun disableButton() {
        binding.buttonContinue.isEnabled = false
        binding.buttonContinue.setTextColor(resources.getColorStateList(R.color.md_theme_secondary_disable))
        binding.buttonContinue.backgroundTintList = resources.getColorStateList(R.color.md_theme_primary_disable)
    }

    private fun observeViewModel() {
        viewModel.grandTotalLD.observe(viewLifecycleOwner) {
            binding.textTotalPrice.text = convertToRupiah(it)
            if (it == 0) {
                binding.scrollView.visibility = View.GONE
                binding.buttonParent.visibility = View.GONE
                binding.textNoOrder.visibility = View.VISIBLE
            } else {
                binding.scrollView.visibility = View.VISIBLE
                binding.buttonParent.visibility = View.VISIBLE
                binding.textNoOrder.visibility = View.GONE
            }
        }
    }

    private fun setGrandtotal(orderDetails: ArrayList<OrderDetail>){
        var grandTotal = 0
        orderDetails.forEach {od->
            grandTotal += (od.quantity*od.price)
        }
        viewModel.setGrandTotal(grandTotal)
    }

    @SuppressLint("ResourceType")
    private fun buildUiKit() {
        UiKitApi.Builder()
            .withContext(requireContext())
            .withMerchantUrl("https://restoapp.fly.dev/api/")
            .withMerchantClientKey("SB-Mid-client-3bwg6AeHieg8hIXf")
            .enableLog(true)
            .withColorTheme(CustomColorTheme(getString(R.color.md_theme_secondary), getString(R.color.md_theme_secondary),getString(R.color.md_theme_secondary)))
            .build()
        uiKitCustomSetting()
    }

    private fun uiKitCustomSetting() {
        val uIKitCustomSetting = UiKitApi.getDefaultInstance().uiKitSetting
        uIKitCustomSetting.saveCardChecked = true
    }

    private fun checkOrderDetail(orderDetails: ArrayList<OrderDetail>){
        if(orderDetails.isEmpty()){
            binding.scrollView.visibility = View.GONE
            binding.buttonParent.visibility = View.GONE
            binding.textNoOrder.visibility = View.VISIBLE
        }else{
            binding.scrollView.visibility = View.VISIBLE
            binding.buttonParent.visibility = View.VISIBLE
            binding.textNoOrder.visibility = View.GONE
        }
    }

    private fun scheduledOrder(){
        binding.layoutSchedule.visibility = View.VISIBLE
    }

    private fun notScheduledOrder(){
        binding.layoutSchedule.visibility = View.GONE
    }
}