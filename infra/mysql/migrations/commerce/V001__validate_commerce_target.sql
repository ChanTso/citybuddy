SELECT CASE
         WHEN DATABASE() = 'commerce_db' THEN 'commerce_db validated for commerce migrations'
         ELSE NULL
       END AS target_validation;
