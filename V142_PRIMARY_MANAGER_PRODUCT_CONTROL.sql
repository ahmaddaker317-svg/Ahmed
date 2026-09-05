-- ADT Pro V142
-- 1) إضافة صنف جديد: مدير الفريق الرئيسي فقط.
-- 2) الصنف الجديد لا يُحفظ بدون سعر شراء أكبر من صفر وسعر بيع أكبر من صفر.
-- 3) تعديل سعر الشراء وعدد القطع داخل الوحدة: مدير الفريق الرئيسي فقط.
-- 4) المدير المساعد/العضو المسموح له لا يستطيع تعديل أي حقل في الصنف غير سعر البيع.
-- شغّل هذا الملف مرة واحدة في Supabase SQL Editor قبل اختبار V142.

CREATE OR REPLACE FUNCTION public.adt_guard_primary_manager_product_fields()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
BEGIN
  -- كل الحفظ المسموح يمر من adt_product_save_v131 بعد التحقق من الصلاحية.
  IF current_setting('adt.primary_manager_product_save', true) = '1' THEN
    RETURN NEW;
  END IF;

  -- حماية احتياطية للحقول الحساسة عند أي كتابة مباشرة تتجاوز مسار الحفظ الآمن.
  IF TG_OP = 'INSERT' THEN
    RAISE EXCEPTION 'Product creation must use the protected ADT save function';
  END IF;

  NEW.buy_price := OLD.buy_price;
  NEW.pack_qty := OLD.pack_qty;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS adt_products_primary_manager_buy_price_guard ON public.products;
DROP TRIGGER IF EXISTS adt_products_primary_manager_product_fields_guard ON public.products;

CREATE TRIGGER adt_products_primary_manager_product_fields_guard
BEFORE INSERT OR UPDATE OF buy_price, pack_qty ON public.products
FOR EACH ROW
EXECUTE FUNCTION public.adt_guard_primary_manager_product_fields();

CREATE OR REPLACE FUNCTION public.adt_product_save_v131(
  p_team_id text,
  p_user_id text,
  p_dev_pin text,
  p_product jsonb,
  p_old_name text
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
DECLARE
  v_is_primary_manager boolean := false;
  v_saved boolean := false;
  v_product jsonb := coalesce(p_product, '{}'::jsonb);
  v_existing public.products%rowtype;
  v_exists boolean := false;
  v_buy numeric := 0;
  v_sell numeric := 0;
  v_can_edit_sell boolean := false;
BEGIN
  -- المدير الرئيسي هو سجل المدير الأصلي/الأقدم للفريق، وهو نفس السجل
  -- الذي تربطه دالة adt_manager_login بجهاز المدير عند تسجيل الدخول.
  -- لا يكفي role='manager' وحده، حتى لا يحصل أي مدير إضافي على صلاحيات المالك.
  SELECT EXISTS (
    SELECT 1
    FROM public.team_members tm
    WHERE tm.id = (
      SELECT pm.id
      FROM public.team_members pm
      WHERE pm.team_id::text = p_team_id
        AND pm.role = 'manager'
      ORDER BY pm.created_at ASC NULLS LAST, pm.id ASC
      LIMIT 1
    )
      AND tm.team_id::text = p_team_id
      AND tm.user_id = p_user_id
      AND tm.role = 'manager'
      AND tm.status = 'approved'
  ) INTO v_is_primary_manager;

  SELECT prod.* INTO v_existing
  FROM public.products prod
  WHERE prod.team_id::text = p_team_id
    AND prod.id::text = nullif(v_product->>'id', '')
  LIMIT 1;
  v_exists := FOUND;

  -- الإنشاء الجديد للمدير الرئيسي فقط.
  IF NOT v_exists AND NOT v_is_primary_manager THEN
    RETURN false;
  END IF;

  -- عند إنشاء صنف جديد يجب إدخال سعري الشراء والبيع فعلياً.
  IF NOT v_exists THEN
    BEGIN v_buy := coalesce(nullif(v_product->>'buy_price','')::numeric, 0); EXCEPTION WHEN others THEN v_buy := 0; END;
    BEGIN v_sell := coalesce(nullif(v_product->>'sell_price','')::numeric, 0); EXCEPTION WHEN others THEN v_sell := 0; END;
    IF v_buy <= 0 OR v_sell <= 0 THEN
      RETURN false;
    END IF;
  END IF;

  -- غير المدير الرئيسي: يجب أن تكون صلاحية تعديل المنتجات (can_edit_products) مفعلة؛ وفي V142 تعني لغير المدير الرئيسي تعديل سعر البيع فقط، ولا يُسمح له بتغيير أي حقل آخر.
  IF v_exists AND NOT v_is_primary_manager THEN
    SELECT coalesce(tm.can_edit_products,false)
      INTO v_can_edit_sell
    FROM public.team_members tm
    WHERE tm.team_id::text = p_team_id
      AND tm.user_id = p_user_id
      AND tm.status = 'approved'
    LIMIT 1;

    IF NOT coalesce(v_can_edit_sell,false) THEN
      RETURN false;
    END IF;

    -- احتفظ بسعر البيع المطلوب فقط، ثم أعد بناء الحمولة من سجل المنتج الأصلي بالكامل.
    -- بهذا لا يستطيع غير المدير الرئيسي تغيير أي حقل آخر حتى لو عدّل الطلب يدوياً.
    BEGIN v_sell := coalesce(nullif(v_product->>'sell_price','')::numeric, coalesce(v_existing.sell_price,0));
    EXCEPTION WHEN others THEN v_sell := coalesce(v_existing.sell_price,0); END;
    v_product := to_jsonb(v_existing);
    v_product := jsonb_set(v_product, '{sell_price}', to_jsonb(v_sell), true);
  END IF;

  -- السماح للوظيفة الأصلية بالكتابة بعد أن تم التحقق/تنظيف الحمولة هنا.
  PERFORM set_config('adt.primary_manager_product_save', '1', true);
  PERFORM set_config('adt.primary_manager_buy_price', CASE WHEN v_is_primary_manager THEN '1' ELSE '0' END, true);

  EXECUTE 'SELECT public.adt_product_save($1,$2,$3,$4,$5)'
  INTO v_saved
  USING p_team_id, p_user_id, p_dev_pin, v_product, p_old_name;

  RETURN coalesce(v_saved, false);
END;
$function$;

REVOKE ALL ON FUNCTION public.adt_product_save_v131(text,text,text,jsonb,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.adt_product_save_v131(text,text,text,jsonb,text) TO anon, authenticated;

-- تحقق موحد للواجهة من هوية المدير الرئيسي؛ نفس قاعدة الحماية المستخدمة في الحفظ.
CREATE OR REPLACE FUNCTION public.adt_is_primary_manager(p_team_id text, p_user_id text)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
  SELECT EXISTS (
    SELECT 1
    FROM public.team_members tm
    WHERE tm.id = (
      SELECT pm.id
      FROM public.team_members pm
      WHERE pm.team_id::text = p_team_id
        AND pm.role = 'manager'
      ORDER BY pm.created_at ASC NULLS LAST, pm.id ASC
      LIMIT 1
    )
      AND tm.team_id::text = p_team_id
      AND tm.user_id = p_user_id
      AND tm.role = 'manager'
      AND tm.status = 'approved'
  );
$function$;

REVOKE ALL ON FUNCTION public.adt_is_primary_manager(text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.adt_is_primary_manager(text,text) TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
