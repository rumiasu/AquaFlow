// 离线合成数据，仅投影当前 WXML/WXSS；不调用 Page 生命周期或真实业务。
const station = { viewStationId:11, stationId:11, stationName:'清泉 NaturalWater 社区服务站', orderStationName:'清泉 NaturalWater 社区服务站' }
const product = { id:5, productId:5, name:'NaturalWater 天然饮用水（家庭装）', productName:'NaturalWater 天然饮用水（家庭装）', spec:'18.9L', specText:'18.9L', productSpec:'18.9L', category:1, quantity:2, quantityUnit:'桶', price:20, effectivePrice:20, deposit:60, effectiveDeposit:60, subtotalText:'40.00', imageUrl:'/assets/product/barrel-neutral-template.webp' }
const address = {id:3,name:'测试顾客',phone:'138****0000',detail:'测试区清泉路 28 号 3 栋 1202 室（合成地址）'}
const order = {id:1208,status:1,statusText:'待配送',payState:'UNPAID',payStateText:'未付款',payHint:'付款完成后水站会安排配送',totalAmount:46,amount:46,canRepay:true,canCancel:true,stationId:11,stationName:station.stationName,addressDetail:address.detail,createTime:'2026-10-22 15:30',customerNote:'客户留言 Original：送达前请联系。',itemSummary:product.name+' 2 桶',items:[product]}
const fee = {waterText:'40.00',depositText:'0.00',extraDepositText:'0.00',deliveryFeeText:'4.00',floorFeeText:'2.00',totalText:'46.00',hasDeliveryFee:true,hasFloorFee:true,hasDeposit:false}
const summary = {independentRights:true,rightBuckets:4,availableRights:2,occupiedBuckets:3,depositBalance:240,owedBuckets:0,storageBuckets:1}
const holding = {...product, assetQty:4,heldTotalQty:3,occupiedQty:3,inTransitQty:0,owedQty:0,storageQty:1,availableRights:2,independentRights:true}
const preview = {refundAmount:120,blocked:false,frozenRefundAmount:30,availableRefundAmount:90,lots:[{lotId:7,qty:2,unitPrice:60}],hasMigratedPrice:true,storageQty:1}
const checkout = {...station,loading:false,products:[product],address,addressText:address.detail,stationNameStationId:11,quoteReady:true,feeBreakdownReady:true,blocked:false,quoteLoading:false,selectedMethod:1,payMethods:[{id:1,name:'微信支付',desc:'以实际付款结果为准',enabled:true},{id:2,name:'货到付款',desc:'经水站开通后可用',enabled:true},{id:3,name:'水票支付',desc:'水票余额不足',enabled:false}],totalWaterCostText:'40.00',payableAmountText:'46.00',deliveryFeeText:'4.00',floorFeeText:'2.00',feeTotalText:'6.00'}
const ticket = {...station,loading:false,totalTickets:12,accounts:[{...product,id:9,remainQuantity:12}],records:[],currentStationId:11,buyProducts:[{...product,effectiveTicketPrice:18}],accountsFailed:false,recordsFailed:false}
const tier = {qty:20,packageId:8,title:'20 张家庭常备水票',price:340,unitPrice:17,source:'CUSTOM'}
const buy = {...ticket,showPurchase:true,buyPackages:[tier],packagesLoaded:true,buyForm:{productId:5,productName:product.name,quantity:20,paymentMethod:1,packageId:8,unifiedQty:null,totalPrice:340,faceValue:17}}
const claim = {...station,ready:true,loading:false,stations:[{id:11,name:station.stationName}],catalog:[product],catalogReady:true,canCreate:false,records:[{id:88,stationId:11,status:'PENDING',statusText:'等待水站核对',createTime:'2026-10-21 10:30',depositText:'240.00',note:'测试历史台账，合成材料。',items:[{productId:5,name:product.name,spec:'18.9L',rights:4,held:3,over:-1,tickets:12,unitPriceText:'60.00'}]}]}
const cases = []
const add = (id,route,state,label) => cases.push({id,route,state,label})
add('order-list','order/list',{loading:false,orders:[order],hasMore:false},'订单列表与独立客户留言')
add('order-list-failed','order/list',{loading:false,orders:[],loadError:'订单暂未加载，请检查网络后重新查询。'},'首读失败与重试')
add('order-detail','order/detail',{...station,detailState:'ready',order,items:[product],statusText:order.statusText,payStatusText:order.payStateText,payHint:order.payHint,payStatusClass:'default',canRepay:true,canCancel:true,cancelLabel:'取消订单',fee,imagesState:'ready'},'订单商品、地址、费用及付款')
add('order-detail-stale','order/detail',{...station,detailState:'ready',order,items:[product],fee,statusText:'待配送',payStatusText:'未付款',refreshNotice:'刷新失败，以下为上次读取的信息，付款与取消需重新核对后操作。'},'陈旧订单信息与恢复')
add('checkout','order/create',checkout,'确认订单与不可用支付方式')
add('checkout-unknown','order/create',{...checkout,quoteReady:false,quoteError:'最新金额与支付方式尚未核实，已暂停下单。',blocked:true,submitState:'unknown',unknownResultText:'原单可能已创建，请恢复原提交或查看订单，勿重新下单。'},'报价和原提交未知')
add('checkout-fees','order/create',{...checkout,showDetailPopup:true},'费用明细抽屉')
add('checkout-confirm','order/create',{...checkout,showUnifiedConfirm:true,selectedMethod:2,shortageItems:[{productId:5,productName:product.name,requested:2,stock:1}],hasInTransitBarrels:true,extraDepositBuckets:1,extraDepositAmountText:'60.00'},'缺货与货到付款综合确认')
add('result-loading','order/success',{loadState:'loading'},'正在核对结果')
add('result-failed','order/success',{loadState:'failed',loadErrorText:'这张订单暂时无法读取，请重新查询或在订单列表核对。'},'结果读取失败')
add('result-unpaid','order/success',{...station,loadState:'ok',orderId:1208,heroTitle:'待配送',heroDesc:'本单尚未完成付款，请核对原订单。',heroTone:'warn',amountText:'¥46.00',canRepay:true},'未付款结果保留警示')
add('barrel','barrel/index',{...station,loading:false,summary,customerBarrelAsset:[holding],maxReturnQty:2,expandedProductId:5},'桶与押金及展开明细')
add('barrel-return','barrel/index',{...station,loading:false,summary,customerBarrelAsset:[holding],maxReturnQty:2,showReturnModal:true,preview,previewReady:true,returnForm:{productId:5,quantity:2,note:'搬家（合成说明）',pickupMode:'STORE',pickupModeText:'到店办理'}},'退还预览、暂留金额与原安排')
add('barrel-refund','barrel/index',{...station,loading:false,summary,customerBarrelAsset:[holding],records:[{id:7,type:2,quantity:2,statusText:'已批准，待交接',returnIdentityText:'退还申请 #7',returnProductName:product.name,depositRefund:120,refundProgress:{paid:30,remaining:90,verified:true},returnDetail:{status:'APPROVED',pickupModeText:'单独上门',requiredBarrels:1,pickupFee:5,statusText:'取桶费与新安排待确认',arrangementRequiresConfirmation:true,customerConfirmationCurrent:false},note:'本人说明 Original'}]},'退还记录与实际退款责任')
add('barrel-purchase','barrel/purchase',{...station,productsLoaded:true,products:[product],quote:{amount:60,notice:'本次只办理押金，不送水和桶。'},recoveryEntries:[{key:'br-synthetic-001',summary:'原水站 11 · 商品 5 · 1 份',active:true,known:false}],originalActive:true,recoveryError:'原购买结果尚未确认，请查询或恢复原请求。'},'独立押金与未知原购买')
add('ticket','ticket/index',ticket,'水票余额与适用商品')
add('ticket-tiers','ticket/index',buy,'购票商品、档位与付款')
add('ticket-tier-error','ticket/index',{...buy,buyPackages:[],packagesLoaded:false,packagesError:'购票档位暂未加载，请重试；散买是否可用以本商品单张票价为准。'},'购票档位失败与就地重试')
add('ticket-recovery','ticket/index',{...ticket,pendingPurchase:{body:{quantity:20},result:null},purchaseRecoveryText:'原购票 NaturalWater 20 张，结果尚未确认，请先查询或恢复原购买。'},'水票原购买恢复')
add('shop','shop/index',{...station,isLogin:true,loading:false,currentStation:{id:11,name:station.stationName},currentStationId:11,allProducts:[product],visibleProducts:[product]},'商城商品与清单动作')
add('shop-picker-error','shop/index',{...station,isLogin:true,loading:false,currentStationId:11,showStationPicker:true,stationList:[],stationListError:'可选水站暂未加载，请重新读取。'},'商城选站失败')
add('template-form','template/index',{loading:false,loaded:true,showEditModal:true,isEditing:true,editingTemplate:{id:8},products:[product],addresses:[{...address,addressText:address.detail}],formReady:true,saveError:'配置未能保存，输入已保留，请重试。',form:{name:'每周订水 Original',addressId:3,specialNote:'放门口（合成）',items:[{productId:5,quantity:2}]}},'配置保存失败保输入')
add('mine','mine/index',{...station,isLogin:true,userInfo:{nickname:'测试顾客 Original'},depositLoaded:true,ticketLoaded:true,barrelLoaded:true,depositText:'240',ticketText:'12',barrelText:'3',barrelHint:'账面桶数，非实物盘点',unloadedCount:0,staleAssetsText:''},'我的资产已加载')
add('mine-guest','mine/index',{isLogin:false},'游客账户与服务入口')
add('profile','mine/edit',{nickname:'测试顾客 Original',phone:'13800000000',saveError:'资料未能保存，输入已保留，请重试。'},'资料保存失败')
add('login','login/index',{loginError:'微信登录未完成，请检查网络后手动重试。',showDevLogin:false},'登录失败与只读协议入口')
add('service','service/index',{isLogin:true,serviceInfo:{phone:'',stationName:station.stationName},stationSelected:true,phoneLoaded:false,phoneError:'水站电话暂未加载，请重试。',refundOptionsLoaded:true,refundOptionsError:'退款记录暂未加载，请重试。',historyLoaded:true,history:[],myDisputes:[],myDisputesLoading:false},'电话及退款选择失败')
add('service-refund','service/index',{isLogin:true,refundRef:{refundType:'BARREL_RETURN',refundId:7,objectText:'原站退押金申请 #7'},refundReady:true,pendingRefundNote:true,content:'补充说明 Original，尚未收到余款。',dispute:{statusText:'水站已登记结案',lastResult:'部分实退已登记，余款待核实。',canOpen:true,actions:[{id:1,actionText:'水站登记处理结果',createTime:'2026-10-22 14:30',reason:'继续核对原申请与原款。'}]},pendingDispute:true,disputeReason:'我还没有收到余款（合成说明）'},'退款异议、结案与原说明恢复')
add('about','mine/about',{},'关于与协议导航')
add('agreement','mine/agreement/index',{loading:false,error:'协议暂时无法加载，请重试。',fallbackNotice:'以下为上次读取的正文，最新版本尚未核实。',doc:{docTitle:'用户协议（合成展示）',updatedAt:'2026-10-22',versionId:'draft-synthetic',notice:'本页仅供阅读，不会记录同意。',sections:[{heading:'一、服务与站别',body:'水票、桶与押金按水站分别记录。条款正文以实际版本为准。'},{heading:'二、退款核对',body:'申请、实物交接与实际退款需分别核对。这里是合成排版文本。'}]}},'协议失败、草稿与只读边界')
add('account-data','mine/account-data/index',{options:{intakeEnabled:false,closureCheckSupported:true,notice:'当前资料请求尚未开放。'},items:[],check:{message:'仍有未结事项，请先核对。',blockingItems:[{stationName:station.stationName,label:'原押金退款尚未完成'}]}},'受理未开放与注销前检查')
add('account-pending','mine/account-data/index',{options:{intakeEnabled:true,requestTypes:[{value:'ACCESS',label:'查阅本人资料'}]},selectedIndex:0,selectedLabel:'查阅本人资料',note:'查看本人历史资料（合成）',pending:true,submitNotice:'登记结果尚未确认；重试沿用原提交编号和内容。',items:[{id:9,requestTypeText:'查阅本人资料',statusText:'已登记'}]},'原账户请求与未知结果')
add('payments','payment/records',{loading:false,loaded:true,error:'刷新失败，当前为上次成功查询的记录。请重试。',records:[{id:9,purposeText:'独立桶押金',methodText:'现金',amountText:'120.00',status:1,statusText:'待确认收款',timeText:'2026-10-22 14:20'},{id:8,orderId:1208,purposeText:'订单付款',methodText:'微信支付',amountText:'46.00',status:2,statusText:'已付款',timeText:'2026-10-21 12:10'}]},'付款记录陈旧提示与用途')
add('deposits','deposit/records/index',{...station,loading:false,loaded:true,errorText:'刷新失败，以下为上次读取的流水。',records:[{id:9,typeText:'退还押金',amountText:'-60.00',amountState:'out',note:'原站实际退还登记（合成）',timeText:'2026-10-22 14:20',orderText:''},{id:8,typeText:'收取押金',amountText:'+120.00',amountState:'in',timeText:'2026-10-20 12:10',orderText:'订单 #1208'}]},'押金流水正负方向与刷新失败')
add('claims','mine/asset-claims/index',claim,'历史认领完整记录')
add('claims-pending','mine/asset-claims/index',{...claim,pending:true,notice:'上次提交结果尚未确认，请恢复原申请。原清单和编号已保留。',form:{depositBalance:'240.00',note:'测试历史台账，合成材料。',items:[{productId:5,name:product.name,rights:4,held:3,tickets:12,unitPrice:60}]}},'原认领清单恢复')
module.exports = cases
