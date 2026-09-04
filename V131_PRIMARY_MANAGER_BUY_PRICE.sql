-- ADT Pro V131
-- قفل تعديل سعر الشراء على مدير الفريق الرئيسي فقط.
-- شغّل الملف مرة واحدة في Supabase SQL Editor قبل اختبار النسخة 1.31.

CREATE OR REPLACE FUNCTION public.adt_guard_primary_manager_buy_price()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'extensions'
AS $function$
BEGIN
  -- لا يسمح بكتابة سعر الشراء إلا من المسار الآمن بعد التحقق من المدير الرئيسي.
  IF current_setting('adt.primary_manager_buy_price', true) = '1' THEN
    RETURN NEW;
  END IF;

  IF TG_OP = 'INSERT' THEN
    NEW.buy_price := 0;
  ELSE
    NEW.buy_price := OLD.buy_price;
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS adt_products_primary_manager_buy_price_guard
ON public.products;

CREATE TRIGGER adt_products_primary_manager_buy_price_guard
BEFORE INSERT OR UPDATE OF buy_price ON public.products
FOR EACH ROW
EXECUTE FUNCTION public.adt_guard_primary_manager_buy_price();

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
  v_existing_buy_price numeric := 0;
BEGIN
  SELECT EXISTS (
    SELECT 1
    FROM public.team_members tm
    WHERE tm.team_id::text = p_team_id
      AND tm.user_id = p_user_id
      AND tm.role = 'manager'
      AND (tm.status = 'approved' OR tm.role = 'manager')
  )
  INTO v_is_primary_manager;

  -- ننظف الحمولة قبل دخولها لدالة الحفظ الأصلية حتى لا تسجل أي آثار جانبية
  -- لسعر حاول المدير المساعد أو العضو تغييره.
  IF NOT v_is_primary_manager THEN
    SELECT coalesce(prod.buy_price, 0)
    INTO v_existing_buy_price
    FROM public.products prod
    WHERE prod.team_id::text = p_team_id
      AND prod.id::text = nullif(v_product->>'id', '')
    LIMIT 1;

    v_product := jsonb_set(
      v_product,
      '{buy_price}',
      to_jsonb(coalesce(v_existing_buy_price, 0)),
      true
    );
  END IF;

  -- قيمة محلية للمعاملة الحالية فقط، ويقرأها Trigger حماية سعر الشراء.
  PERFORM set_config(
    'adt.primary_manager_buy_price',
    CASE WHEN v_is_primary_manager THEN '1' ELSE '0' END,
    true
  );

  -- نستخدم دالة الحفظ الأصلية حتى تظل كل صلاحيات وتحديثات المنتج الحالية كما هي.
  EXECUTE 'SELECT public.adt_product_save($1,$2,$3,$4,$5)'
  INTO v_saved
  USING p_team_id, p_user_id, p_dev_pin, v_product, p_old_name;

  RETURN coalesce(v_saved, false);
END;
$function$;

REVOKE ALL
ON FUNCTION public.adt_product_save_v131(text,text,text,jsonb,text)
FROM PUBLIC;

GRANT EXECUTE
ON FUNCTION public.adt_product_save_v131(text,text,text,jsonb,text)
TO anon, authenticated;

NOTIFY pgrst, 'reload schema';
